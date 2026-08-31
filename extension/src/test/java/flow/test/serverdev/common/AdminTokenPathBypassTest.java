package flow.test.serverdev.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.storage.ObjectStorage;
import flow.test.serverdev.storage.StorageKey;
import flow.test.serverdev.storage.StorageKeyGenerator;
import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 경로 변형으로 관리 토큰 검사를 우회할 수 있는가. (외부 리뷰 CodeRabbit)
 *
 * <p><b>MockMvc 로는 답할 수 없는 질문이다.</b> MockMvc 는 서블릿 컨테이너를 거치지 않아
 * URI 디코딩·정규화가 실제와 다르다. 우회가 되느냐는 결국 <b>톰캣이 무엇을 넘겨주고
 * 스프링이 무엇으로 매핑하느냐</b>의 문제이므로, 진짜 서버를 띄우고 날 것의 HTTP 로 친다.
 *
 * <p>{@link HttpClient} 를 쓰는 이유도 같다. {@code RestTemplate} 계열은 URI 를 정규화·
 * 인코딩해버려서 <b>보내려던 변형이 서버에 닿지 않는다.</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("관리 토큰 — 경로 변형 우회")
class AdminTokenPathBypassTest extends IntegrationTest {

	private static final byte[] BODY = "bypass probe".getBytes(StandardCharsets.UTF_8);

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectStorage storage;

	@Autowired
	private StorageKeyGenerator keyGenerator;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
		jdbc.update("DELETE FROM upload_audit");
	}

	private UUID storeAllowed(String filename) {
		StorageKey key = keyGenerator.generate();
		storage.store(key, new ByteArrayInputStream(BODY), BODY.length);
		jdbc.update("""
			INSERT INTO upload_audit (original_filename, size_bytes, result, stored_key, file_id)
			VALUES (?, ?, 'ALLOWED', ?, ?::uuid)
			""", filename, (long) BODY.length, key.value(), key.fileId().toString());
		return key.fileId();
	}

	private boolean stillAlive(UUID fileId) {
		Long alive = jdbc.queryForObject("""
			SELECT count(*) FROM upload_audit
			WHERE file_id = ?::uuid AND result = 'ALLOWED' AND deleted_at IS NULL
			""", Long.class, fileId.toString());
		return alive != null && alive == 1L;
	}

	/** 날 것의 경로를 그대로 보낸다. {@code URI.create} 는 주어진 문자열을 손대지 않는다. */
	private int deleteRaw(String rawPath) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + rawPath))
			.DELETE()
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
	}

	/**
	 * ★ 이 목록은 <b>실측 결과</b>다. 수정 전에 두 변형이 토큰 없이 204 를 받고
	 * 파일을 실제로 지웠다.
	 *
	 * <pre>
	 *   원본 (대조군)          401   보호됨
	 *   /api/%66iles/{id}      204 ★ 삭제됨
	 *   /%61pi/%66il%65s/{id}  204 ★ 삭제됨
	 *   //api/files/{id}       400   톰캣이 거부
	 *   /api//files/{id}       400   톰캣이 거부
	 *   /API/FILES/{id}        404   매핑이 대소문자를 구분
	 *   /api/files/{id}/       401   보호됨
	 *   /api/./files/{id}      400   톰캣이 거부
	 *   /api/nowhere/../files  400   톰캣이 거부
	 *   /api/files;v=1/{id}    400   톰캣이 거부
	 * </pre>
	 */
	private static Map<String, String> variants() {
		Map<String, String> variants = new LinkedHashMap<>();
		variants.put("원본 (대조군)", "/api/files/%s");
		variants.put("퍼센트 인코딩된 f", "/api/%%66iles/%s");
		variants.put("경로 전체 인코딩", "/%%61pi/%%66il%%65s/%s");
		variants.put("선행 이중 슬래시", "//api/files/%s");
		variants.put("중간 이중 슬래시", "/api//files/%s");
		variants.put("대문자 경로", "/API/FILES/%s");
		variants.put("후행 슬래시", "/api/files/%s/");
		variants.put("현재 디렉터리 세그먼트", "/api/./files/%s");
		variants.put("상위 디렉터리 우회", "/api/nowhere/../files/%s");
		variants.put("매트릭스 파라미터", "/api/files;v=1/%s");
		return variants;
	}

	/**
	 * ★★ 지켜야 하는 성질은 하나다 — <b>토큰 없는 요청은 파일을 지우지 못한다.</b>
	 *
	 * <p>상태 코드를 전부 401 로 못박지 않는다. 톰캣이 400 으로 먼저 끊는 변형과 매핑이
	 * 404 를 내는 변형이 있는데, 그것들은 <b>우리 필터에 닿지도 않는다.</b> 401 을 요구하면
	 * 우리가 지키지도 않는 것을 지킨다고 주장하게 된다. 2xx 가 아니고 파일이 살아남았는지를
	 * 본다 — 그것이 실제 보안 성질이다.
	 */
	@Test
	@DisplayName("★ 어떤 경로 변형으로도 토큰 없이 삭제할 수 없다")
	void noVariantBypassesTheToken() throws Exception {
		Map<String, Integer> observed = new LinkedHashMap<>();
		Map<String, Boolean> survived = new LinkedHashMap<>();

		for (Map.Entry<String, String> variant : variants().entrySet()) {
			UUID fileId = storeAllowed("probe.pdf");
			observed.put(variant.getKey(), deleteRaw(variant.getValue().formatted(fileId)));
			survived.put(variant.getKey(), stillAlive(fileId));
		}

		assertThat(survived)
			.as("토큰 없는 삭제가 통했다. 관측된 상태 코드: %s", observed)
			.doesNotContainValue(false);
		assertThat(observed.values())
			.as("2xx 는 요청이 컨트롤러까지 갔다는 뜻이다. 관측: %s", observed)
			.noneMatch(status -> status >= 200 && status < 300);
	}

	/**
	 * ★ 위 테스트만으로는 <b>무엇이</b> 막았는지 알 수 없다. 톰캣이 400 으로 끊어도 통과한다.
	 *
	 * <p>인코딩 변형은 톰캣을 그대로 지나 <b>우리 필터까지 온다</b> — 실제로 예전에는 그대로
	 * 컨트롤러까지 갔다. 그러니 이 둘만큼은 401 이어야 한다. 401 이 아니면 우리 필터가
	 * 아니라 우연히 다른 계층이 막고 있는 것이고, 그 계층이 바뀌면 구멍이 다시 열린다.
	 */
	@Test
	@DisplayName("★ 인코딩 변형은 필터가 직접 401 로 막는다 — 다른 계층에 기대지 않는다")
	void encodedVariantsAreRejectedByTheFilter() throws Exception {
		UUID encodedF = storeAllowed("probe.pdf");
		UUID fullyEncoded = storeAllowed("probe.pdf");

		assertThat(deleteRaw("/api/%%66iles/%s".formatted(encodedF)))
			.as("%66 는 f 로 디코딩된다. 매핑이 그렇게 읽으면 필터도 그렇게 읽어야 한다")
			.isEqualTo(401);
		assertThat(deleteRaw("/%%61pi/%%66il%%65s/%s".formatted(fullyEncoded)))
			.as("경로 전체를 인코딩해도 같다")
			.isEqualTo(401);

		assertThat(stillAlive(encodedF)).isTrue();
		assertThat(stillAlive(fullyEncoded)).isTrue();
	}

	/**
	 * 우회를 막느라 공개 경로까지 잠그면 과제 요구("누구나 접속 가능")를 깬다.
	 * 인코딩 변형으로 <b>목록·업로드가 여전히 열려 있는지</b>도 함께 본다.
	 */
	@Test
	@DisplayName("인코딩된 경로라도 목록은 공개다")
	void encodedPathKeepsListPublic() throws Exception {
		storeAllowed("public.pdf");

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/%66iles"))
			.GET()
			.build();

		assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
			.isEqualTo(200);
	}

	/**
	 * 활동 로그는 <b>읽기인데도</b> 보호 대상이다. 인코딩을 바꿔 그 예외를 비켜 갈 수 있으면
	 * 보호했다고 말할 수 없다. 파일 삭제에서 실제로 뚫렸던 것과 같은 변형으로 확인한다.
	 */
	@Test
	@DisplayName("활동 로그는 인코딩을 바꿔도 토큰 없이 열리지 않는다")
	void encodedAuditPathStaysProtected() throws Exception {
		for (String raw : new String[] { "/api/audit", "/api/%61udit", "/%61pi/%61udit" }) {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + raw))
				.GET()
				.build();
			int status = http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();

			assertThat(status / 100).as("%s 가 2xx 를 받으면 안 된다 (실제 %d)", raw, status)
				.isNotEqualTo(2);
		}
	}
}

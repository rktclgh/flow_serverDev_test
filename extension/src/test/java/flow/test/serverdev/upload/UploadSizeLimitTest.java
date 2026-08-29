package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import flow.test.serverdev.support.IntegrationTest;

/**
 * 크기 상한. (SPEC §10.1, §21.10)
 *
 * <p><b>MockMvc 로는 검증할 수 없다.</b> multipart 크기 제한은 서블릿 컨테이너가 요청을 파싱하며
 * 강제하는데 MockMvc 에는 그 컨테이너가 없다. 실제 Tomcat 을 띄워야 이 경로가 재현된다.
 *
 * <p>확인하려는 것이 상태 코드만이 아니다. {@code maxSwallowSize} 를 설정하지 않으면 서버는
 * 남은 바이트를 읽지 않고 커넥션을 끊어 브라우저에 {@code ERR_CONNECTION_RESET} 이 뜬다.
 * 서버 로그에는 정상 거부로 남는데 사용자는 원인 불명 실패를 본다.
 * <b>응답이 도착한다는 것 자체가 검증 대상</b>이며, 그래서 예외가 아니라 상태 코드를 본다.
 *
 * <p><b>HTTP 클라이언트로 JDK {@code HttpClient} 를 쓴다.</b> Boot 4 에서
 * {@code TestRestTemplate} 은 {@code spring-boot-resttestclient} 로 옮겨졌고 그것이 다시
 * {@code spring-boot-restclient} 를 요구한다. 테스트 하나를 위해 의존성 두 개를 들이는 것보다
 * multipart 본문을 직접 조립하는 편이 싸고, 무엇을 보내는지도 더 분명하다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("업로드 크기 상한")
class UploadSizeLimitTest extends IntegrationTest {

	/** {@code max-file-size: 10MB} 를 넘긴다. */
	private static final int OVER_LIMIT = 11 * 1024 * 1024;

	private static final String BOUNDARY = "----extguardBoundary";

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("10MB 를 넘기면 커넥션이 끊기지 않고 413 JSON 이 온다")
	void tooLarge() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files"))
				.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
				.POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody()))
				.build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.body()).contains("FILE_TOO_LARGE");
	}

	private static byte[] multipartBody() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(("--" + BOUNDARY + "\r\n"
			+ "Content-Disposition: form-data; name=\"file\"; filename=\"big.pdf\"\r\n"
			+ "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		out.write(new byte[OVER_LIMIT]);
		out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}
}

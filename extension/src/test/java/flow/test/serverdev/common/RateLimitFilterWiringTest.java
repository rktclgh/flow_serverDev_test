package flow.test.serverdev.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 속도 제한 <b>배선</b>. (SPEC §21.9)
 *
 * <p>버킷 산수는 {@code RateLimitFilterTest} 가 본다. 여기서 확인하는 것은 필터가
 * <b>요청 하나에 정확히 한 번</b> 걸리는가, 그리고 <b>업로드 경로에만</b> 걸리는가다.
 *
 * <p><b>이중 등록이 이 테스트의 표적이다.</b> {@code @Component} 자동 등록과
 * {@code FilterRegistrationBean} 을 같이 쓰면 같은 요청에서 두 번 차감되어 설정한 burst 의
 * 절반에서 429 가 나간다. 그런데 로그에는 정상적인 거부로만 보인다 — 한도가 반으로 줄었다는
 * 사실을 아무도 눈치채지 못한다. 그래서 "몇 번째 요청이 거부되는가" 를 못박는다.
 *
 * <p>버킷을 소모하는 테스트를 <b>하나만</b> 둔다. 필터는 싱글턴이고 컨텍스트는 클래스 전체가
 * 공유하므로, 소모하는 테스트가 둘이면 결과가 실행 순서에 좌우된다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	// 용량 2 · 분당 1개. 보충이 사실상 없어 "정확히 2개까지" 를 시계 없이 관측할 수 있다.
	"app.rate-limit.burst=2",
	"app.rate-limit.per-minute=1"
})
@DisplayName("속도 제한 배선")
class RateLimitFilterWiringTest extends IntegrationTest {

	private static final byte[] BODY = "hello".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
		jdbc.update("DELETE FROM upload_audit");
	}

	private static MockMultipartFile file() {
		return new MockMultipartFile("file", "report.pdf",
			MediaType.APPLICATION_OCTET_STREAM_VALUE, BODY);
	}

	private long auditCount() {
		return jdbc.queryForObject("SELECT count(*) FROM upload_audit", Long.class);
	}

	@Test
	@DisplayName("설정한 용량만큼만 통과한다 — 이중 차감이 없다")
	void consumesOneTokenPerRequest() throws Exception {
		mockMvc.perform(multipart("/api/files").file(file())).andExpect(status().isCreated());
		mockMvc.perform(multipart("/api/files").file(file())).andExpect(status().isCreated());

		mockMvc.perform(multipart("/api/files").file(file()))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After"))
				.as("언제 다시 오면 되는지는 서버만 안다")
				.isNotNull());

		// ★ 429 는 감사하지 않는다(SPEC §21.2). 통과한 두 건만 남아야 한다 —
		//   기록하면 요청만으로 감사 테이블을 부풀릴 수 있고, 감사 실패는 fail-closed 503 이라
		//   그것이 곧 서비스 정지 수단이 된다.
		assertThat(auditCount()).isEqualTo(2);
	}

	/** 정확 경로 패턴이라 하위 경로(다운로드)와 다른 API 는 대상이 아니다. */
	@Test
	@DisplayName("업로드 경로가 아니면 걸리지 않는다")
	void otherPathsAreUntouched() throws Exception {
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/extensions")).andExpect(status().isOk());
		}
	}
}

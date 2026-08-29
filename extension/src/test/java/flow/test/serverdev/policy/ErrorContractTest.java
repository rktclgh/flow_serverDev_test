package flow.test.serverdev.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import flow.test.serverdev.support.IntegrationTest;

/**
 * <b>애플리케이션이 만들지 않은 실패</b>의 응답 계약. (SPEC §7.7)
 *
 * <p>이 테스트는 실측에서 나왔다. {@code GlobalExceptionHandler} 에 catch-all 만 두었을 때
 * 없는 경로·잘못된 메서드·잘못된 Content-Type 이 <b>전부 500</b> 으로 나가고 있었다.
 * 어느 기존 테스트도 이 경로를 지나지 않았다 — 모든 테스트가 <i>올바른</i> 요청만 보냈기 때문이다.
 *
 * <p>상태 코드가 틀린 것보다 부수 피해가 크다. 공개 배포에서 봇이 없는 경로를 긁을 때마다
 * 스택트레이스가 쌓이고, 404 가 5xx 로 집계되어 오류율 지표가 거짓이 된다.
 */
@AutoConfigureMockMvc
@DisplayName("에러 응답 계약")
class ErrorContractTest extends IntegrationTest {

	private static final String BASE = "/api/extensions";

	@Autowired
	MockMvc mvc;

	@Nested
	@DisplayName("스프링이 판정하는 실패도 우리 형식으로 나간다")
	class FrameworkFailures {

		@Test
		@DisplayName("없는 API 경로는 404 NOT_FOUND")
		void unknownApiPath() throws Exception {
			mvc.perform(get("/api/nope"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}

		@Test
		@DisplayName("없는 정적 경로도 404 — 500 이 아니다")
		void unknownStaticPath() throws Exception {
			mvc.perform(get("/totally/unknown"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}

		@Test
		@DisplayName("지원하지 않는 메서드는 405 METHOD_NOT_ALLOWED")
		void unsupportedMethod() throws Exception {
			mvc.perform(put(BASE)
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
		}

		@Test
		@DisplayName("Content-Type 이 없으면 415 UNSUPPORTED_MEDIA_TYPE")
		void missingContentType() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.content("{\"name\":\"sh\"}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
		}

		@Test
		@DisplayName("JSON 이 아닌 Content-Type 도 415")
		void wrongContentType() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.TEXT_PLAIN)
					.content("sh"))
				.andExpect(status().isUnsupportedMediaType());
		}

		@Test
		@DisplayName("경로 변수가 비어 있으면 404 — 매핑되는 경로가 없다")
		void emptyPathVariable() throws Exception {
			mvc.perform(delete(BASE + "/custom/").header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}

		@Test
		@DisplayName("실패 응답은 모두 JSON 이다 — 프론트가 코드로 분기할 수 있어야 한다")
		void allFailuresAreJson() throws Exception {
			mvc.perform(get("/api/nope"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
		}
	}

	@Nested
	@DisplayName("안전한 메서드는 토큰 없이 통과한다")
	class SafeMethods {

		/**
		 * HEAD 는 GET 과 같은 <b>안전한</b> 메서드다. 상태를 바꾸지 않으므로 보호 대상이 아니다.
		 * 필터가 "GET 만" 통과시키면 헬스체크·모니터링의 HEAD 요청이 401 을 받는다.
		 */
		@Test
		@DisplayName("HEAD 는 토큰 없이 200")
		void headIsPublic() throws Exception {
			mvc.perform(head(BASE)).andExpect(status().isOk());
		}

		/** OPTIONS 는 지원 메서드를 묻는 요청이다. 상태를 바꾸지 않으므로 막을 이유가 없다. */
		@Test
		@DisplayName("OPTIONS 는 토큰 없이 통과한다")
		void optionsIsPublic() throws Exception {
			mvc.perform(options(BASE)).andExpect(status().is2xxSuccessful());
		}

		@Test
		@DisplayName("상태를 바꾸는 메서드는 여전히 토큰이 필요하다")
		void unsafeMethodsStillProtected() throws Exception {
			mvc.perform(patch(BASE + "/fixed/exe")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\":true}"))
				.andExpect(status().isUnauthorized());
		}
	}
}

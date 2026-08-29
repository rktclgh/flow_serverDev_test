package flow.test.serverdev.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 관리 토큰 필터. <b>스프링 컨텍스트 없이</b> 필터만 직접 돌린다.
 *
 * <p>여기서 겨누는 것들은 통합 테스트가 지나가지 않는 경로다. 통합 테스트는 항상 올바르게
 * 설정된 토큰으로 뜨므로 "미설정" 과 "약한 토큰" 은 <b>한 번도 실행되지 않는다</b>.
 * advisory lock 뒤에 있던 {@code ConstraintViolations} 와 같은 종류의 사각지대다.
 */
@DisplayName("관리 토큰 필터")
class AdminTokenFilterTest {

	/** 32자 — SPEC §7.0 의 최소 길이. */
	private static final String VALID_TOKEN = "0123456789abcdef0123456789abcdef";

	@Nested
	@DisplayName("설정 검증 — 기동 시점")
	class Configuration {

		/**
		 * 약한 토큰은 <b>기동을 실패시킨다</b>. 이 필터는 미설정일 때 정책 변경을 전부 거부하는
		 * fail-closed 를 이미 하고 있는데, 짧은 토큰을 조용히 받아들이면 앞뒤가 맞지 않는다.
		 * 설정 실수는 배포 전에 드러나는 편이 낫다.
		 */
		@Test
		@DisplayName("32자 미만 토큰이면 기동에 실패한다")
		void rejectsWeakToken() {
			assertThatThrownBy(() -> new AdminTokenFilter("short-token"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32");
		}

		@Test
		@DisplayName("31자는 거부, 32자는 허용 — 경계값")
		void boundary() {
			assertThatThrownBy(() -> new AdminTokenFilter("a".repeat(31)))
				.isInstanceOf(IllegalStateException.class);
			assertThatCode(() -> new AdminTokenFilter("a".repeat(32)))
				.doesNotThrowAnyException();
		}

		/**
		 * 미설정은 기동을 막지 않는다. 정책 변경만 못 쓰는 상태로 뜨는 편이,
		 * 조회·업로드까지 죽는 것보다 낫다 — 과제의 "누구나 접속 가능" 은 그쪽에 걸린다.
		 */
		@Test
		@DisplayName("미설정은 기동을 막지 않는다 — 변경만 막힌다")
		void unsetIsAllowedToStart() {
			assertThatCode(() -> new AdminTokenFilter("")).doesNotThrowAnyException();
			assertThatCode(() -> new AdminTokenFilter(null)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("공백만 있는 값은 미설정으로 본다 — 길이 검사 대상이 아니다")
		void blankIsTreatedAsUnset() {
			assertThatCode(() -> new AdminTokenFilter("   ")).doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("미설정 상태의 동작 — fail-closed")
	class NotConfigured {

		@Test
		@DisplayName("정책 변경을 거부한다 — ADMIN_TOKEN_NOT_CONFIGURED")
		void rejectsChange() throws Exception {
			MockHttpServletResponse response =
				run(new AdminTokenFilter(""), "POST", "/api/extensions/custom", null);

			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(response.getContentAsString()).contains("ADMIN_TOKEN_NOT_CONFIGURED");
		}

		@Test
		@DisplayName("토큰을 가져와도 거부한다 — 우연히 맞출 수 없다")
		void rejectsEvenWithToken() throws Exception {
			MockHttpServletResponse response =
				run(new AdminTokenFilter(""), "POST", "/api/extensions/custom", "");

			assertThat(response.getStatus()).isEqualTo(401);
		}

		@Test
		@DisplayName("조회는 그대로 열려 있다")
		void readStaysOpen() throws Exception {
			MockHttpServletResponse response =
				run(new AdminTokenFilter(""), "GET", "/api/extensions", null);

			assertThat(response.getStatus()).isEqualTo(200);
		}
	}

	@Nested
	@DisplayName("설정된 상태의 동작")
	class Configured {

		@Test
		@DisplayName("토큰 누락은 ADMIN_TOKEN_REQUIRED")
		void missingToken() throws Exception {
			MockHttpServletResponse response =
				run(new AdminTokenFilter(VALID_TOKEN), "POST", "/api/extensions/custom", null);

			assertThat(response.getContentAsString()).contains("ADMIN_TOKEN_REQUIRED");
		}

		@Test
		@DisplayName("토큰 불일치는 ADMIN_TOKEN_INVALID — 누락과 구분한다")
		void wrongToken() throws Exception {
			MockHttpServletResponse response =
				run(new AdminTokenFilter(VALID_TOKEN), "POST", "/api/extensions/custom", "wrong");

			assertThat(response.getContentAsString()).contains("ADMIN_TOKEN_INVALID");
		}

		@Test
		@DisplayName("올바른 토큰은 통과한다")
		void correctToken() throws Exception {
			MockHttpServletResponse response =
				run(new AdminTokenFilter(VALID_TOKEN), "POST", "/api/extensions/custom", VALID_TOKEN);

			assertThat(response.getStatus()).isEqualTo(200);
		}

		@Test
		@DisplayName("보호 대상이 아닌 경로는 토큰 없이 통과한다")
		void unprotectedPath() throws Exception {
			MockHttpServletResponse response =
				run(new AdminTokenFilter(VALID_TOKEN), "POST", "/api/files", null);

			assertThat(response.getStatus()).isEqualTo(200);
		}
	}

	/** 필터를 한 번 통과시킨다. 체인까지 갔으면 상태가 200 으로 남는다. */
	private MockHttpServletResponse run(AdminTokenFilter filter, String method, String uri,
			String token) throws Exception {

		MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
		if (token != null) {
			request.addHeader("X-Admin-Token", token);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}
}

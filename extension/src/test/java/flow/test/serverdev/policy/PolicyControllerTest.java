package flow.test.serverdev.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;
import org.slf4j.LoggerFactory;

/**
 * 정책 API 의 <b>HTTP 계약</b> 검증. (SPEC §7.0~7.4)
 *
 * <p>도메인 규칙은 {@code PolicyServiceTest} 가 본다. 여기서 확인하는 것은 다르다 —
 * 상태 코드, 응답 형태, 그리고 <b>토큰 없이 정책을 바꿀 수 있는가</b>.
 * 마지막 항목은 이 서비스에서 가장 중요한 접근 통제다. 여기가 뚫리면
 * 누구나 {@code exe} 차단을 해제할 수 있어 정책 자체가 무의미해진다.
 */
@AutoConfigureMockMvc
@DisplayName("정책 API")
class PolicyControllerTest extends IntegrationTest {

	private static final String BASE = "/api/extensions";
	private static final String TOKEN_HEADER = "X-Admin-Token";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
	}

	@Nested
	@DisplayName("접근 통제")
	class AccessControl {

		@Test
		@DisplayName("조회는 토큰 없이 열려 있다 — 과제의 \"누구나 접속 가능\"이 걸리는 지점")
		void readIsPublic() throws Exception {
			mvc.perform(get(BASE))
				.andExpect(status().isOk());
		}

		@Test
		@DisplayName("토큰 없이 토글하면 401 ADMIN_TOKEN_REQUIRED")
		void toggleWithoutToken() throws Exception {
			mvc.perform(patch(BASE + "/fixed/exe")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\":true}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_TOKEN_REQUIRED"));
		}

		@Test
		@DisplayName("틀린 토큰이면 401 ADMIN_TOKEN_INVALID — 누락과 구분해서 알린다")
		void toggleWithWrongToken() throws Exception {
			mvc.perform(patch(BASE + "/fixed/exe")
					.header(TOKEN_HEADER, "wrong-token")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\":true}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_TOKEN_INVALID"));
		}

		@Test
		@DisplayName("토큰 없이 커스텀을 추가할 수 없다")
		void addWithoutToken() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"sh\"}"))
				.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("토큰 없이 커스텀을 삭제할 수 없다")
		void deleteWithoutToken() throws Exception {
			mvc.perform(delete(BASE + "/custom/sh"))
				.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("인증 실패도 JSON 이다 — 프론트가 코드로 분기할 수 있어야 한다")
		void authFailureIsJson() throws Exception {
			mvc.perform(delete(BASE + "/custom/sh"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
		}
	}

	@Nested
	@DisplayName("조회")
	class Query {

		@Test
		@DisplayName("고정 7개가 요구사항 순서로, 상한과 함께 내려온다")
		void shape() throws Exception {
			mvc.perform(get(BASE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fixed.length()").value(7))
				.andExpect(jsonPath("$.fixed[0].name").value("bat"))
				.andExpect(jsonPath("$.fixed[6].name").value("js"))
				.andExpect(jsonPath("$.fixed[0].blocked").value(false))
				.andExpect(jsonPath("$.custom").isArray())
				.andExpect(jsonPath("$.customCount").value(0))
				.andExpect(jsonPath("$.customLimit").value(200));
		}

		@Test
		@DisplayName("MIME 스니핑 차단 헤더가 붙는다 — 업로드를 다루는 서비스의 기본")
		void securityHeader() throws Exception {
			mvc.perform(get(BASE))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"));
		}
	}

	@Nested
	@DisplayName("고정 확장자 토글")
	class FixedToggle {

		@Test
		@DisplayName("토큰이 있으면 200 과 바뀐 상태를 돌려준다")
		void toggle() throws Exception {
			mvc.perform(patch(BASE + "/fixed/exe")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("exe"))
				.andExpect(jsonPath("$.blocked").value(true));
		}

		@Test
		@DisplayName("경로의 대문자·앞점도 정규화한다")
		void normalizesPathVariable() throws Exception {
			mvc.perform(patch(BASE + "/fixed/.EXE")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("exe"));
		}

		@Test
		@DisplayName("고정 7개가 아니면 404 EXT_NOT_FOUND")
		void unknownName() throws Exception {
			mvc.perform(patch(BASE + "/fixed/sh")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\":true}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EXT_NOT_FOUND"));
		}

		/**
		 * DTO 를 {@code Boolean} 래퍼로 둔 이유의 실증. {@code boolean} 이었다면 필드 누락이
		 * 조용히 {@code false} 로 처리되어 <b>"차단 해제"라는 정반대 동작</b>이 일어난다.
		 */
		@Test
		@DisplayName("blocked 필드가 없으면 400 — false 로 조용히 처리되지 않는다")
		void missingBlockedField() throws Exception {
			mvc.perform(patch(BASE + "/fixed/exe")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));

			Boolean blocked = jdbc.queryForObject(
				"SELECT is_blocked FROM blocked_extension WHERE name = 'exe'", Boolean.class);
			assertThat(blocked).isFalse();
		}

		@Test
		@DisplayName("본문이 깨져 있으면 400 — 파서 메시지를 노출하지 않는다")
		void brokenBody() throws Exception {
			mvc.perform(patch(BASE + "/fixed/exe")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\":"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."));
		}
	}

	@Nested
	@DisplayName("커스텀 추가")
	class CustomAdd {

		@Test
		@DisplayName("201 과 정규화된 이름을 돌려준다 — 화면이 변환 사실을 알릴 수 있다")
		void created() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\".SH \"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("sh"));
		}

		@Test
		@DisplayName("중복은 409 EXT_DUPLICATE")
		void duplicate() throws Exception {
			addCustom("sh");

			mvc.perform(post(BASE + "/custom")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"sh\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EXT_DUPLICATE"));
		}

		@Test
		@DisplayName("고정과 겹치면 409 와 함께 어디서 처리할지 알려준다")
		void fixedConflict() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"exe\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EXT_FIXED_CONFLICT"))
				.andExpect(jsonPath("$.message").value(
					"exe는 고정 확장자입니다. 고정 확장자 영역에서 체크하세요."))
				.andExpect(jsonPath("$.detail.policyType").value("FIXED"));
		}

		@Test
		@DisplayName("허용되지 않는 문자는 400 EXT_INVALID_FORMAT")
		void invalidFormat() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"s h\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EXT_INVALID_FORMAT"));
		}

		@Test
		@DisplayName("20자 초과는 400 EXT_TOO_LONG — 형식 오류와 구분한다")
		void tooLong() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"" + "a".repeat(21) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EXT_TOO_LONG"));
		}

		/**
		 * 빈 문자열은 정규화기가 판정하고({@code EXT_INVALID_FORMAT}),
		 * <b>필드 누락</b>만 요청 구조 오류({@code REQUEST_INVALID})다.
		 * 값에 대한 판정을 한 곳에서만 하기 위해 DTO 에 {@code @NotBlank} 를 두지 않았다.
		 */
		@Test
		@DisplayName("빈 문자열은 값 판정이라 EXT_INVALID_FORMAT")
		void emptyName() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EXT_INVALID_FORMAT"));
		}

		@Test
		@DisplayName("name 필드 누락은 구조 오류라 REQUEST_INVALID")
		void missingName() throws Exception {
			mvc.perform(post(BASE + "/custom")
					.header(TOKEN_HEADER, ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		}
	}

	@Nested
	@DisplayName("커스텀 삭제")
	class CustomDelete {

		@Test
		@DisplayName("204 로 응답하고 본문이 없다")
		void deleted() throws Exception {
			addCustom("sh");

			mvc.perform(delete(BASE + "/custom/sh").header(TOKEN_HEADER, ADMIN_TOKEN))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		}

		@Test
		@DisplayName("없는 이름은 404 EXT_NOT_FOUND")
		void missing() throws Exception {
			mvc.perform(delete(BASE + "/custom/sh").header(TOKEN_HEADER, ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EXT_NOT_FOUND"));
		}

		@Test
		@DisplayName("고정 확장자는 409 EXT_FIXED_NOT_DELETABLE — 해제 방법을 안내한다")
		void fixedIsNotDeletable() throws Exception {
			mvc.perform(delete(BASE + "/custom/exe").header(TOKEN_HEADER, ADMIN_TOKEN))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EXT_FIXED_NOT_DELETABLE"))
				.andExpect(jsonPath("$.message").value(
					"exe는 고정 확장자라 삭제할 수 없습니다. 체크를 해제하세요."));
		}
	}

	@Nested
	@DisplayName("감사 로그")
	class AuditLog {

		/**
		 * 정책 변경 기록은 <b>실제로 바뀐 값</b>을 남겨야 한다. 입력값을 그대로 적으면
		 * 정규화가 개입한 요청에서 기록과 저장소가 어긋난다 — 감사 기록이 거짓이 된다.
		 */
		@Test
		@DisplayName("삭제 기록은 정규화된 이름을 남긴다")
		void deleteLogsNormalizedName() throws Exception {
			addCustom("sh");
			ListAppender<ILoggingEvent> appender = attachAppender();

			try {
				mvc.perform(delete(BASE + "/custom/.SH").header(TOKEN_HEADER, ADMIN_TOKEN))
					.andExpect(status().isNoContent());

				assertThat(appender.list)
					.extracting(ILoggingEvent::getFormattedMessage)
					.anySatisfy(message -> assertThat(message)
						.contains("action=CUSTOM_DELETE")
						.contains("extension=sh"));
			}
			finally {
				detachAppender(appender);
			}
		}

		@Test
		@DisplayName("토글 기록도 정규화된 이름을 남긴다")
		void toggleLogsNormalizedName() throws Exception {
			ListAppender<ILoggingEvent> appender = attachAppender();

			try {
				mvc.perform(patch(BASE + "/fixed/.EXE")
						.header(TOKEN_HEADER, ADMIN_TOKEN)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"blocked\":true}"))
					.andExpect(status().isOk());

				assertThat(appender.list)
					.extracting(ILoggingEvent::getFormattedMessage)
					.anySatisfy(message -> assertThat(message)
						.contains("action=FIXED_BLOCK")
						.contains("extension=exe"));
			}
			finally {
				detachAppender(appender);
			}
		}
	}

	private ListAppender<ILoggingEvent> attachAppender() {
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		((Logger) LoggerFactory.getLogger(PolicyAuditLogger.class)).addAppender(appender);
		return appender;
	}

	private void detachAppender(ListAppender<ILoggingEvent> appender) {
		((Logger) LoggerFactory.getLogger(PolicyAuditLogger.class)).detachAppender(appender);
	}

	private void addCustom(String name) throws Exception {
		mvc.perform(post(BASE + "/custom")
				.header(TOKEN_HEADER, ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\"}"))
			.andExpect(status().isCreated());
	}
}

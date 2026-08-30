package flow.test.serverdev.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 정책 변경 이력. (`policy_change_log`)
 *
 * <p><b>왜 필요한가.</b> {@code upload_audit} 은 "무엇이 왜 차단됐는가" 를 답하지만, 그 판정의
 * 기준이던 정책이 언제 어떻게 바뀌었는지는 어디에도 없었습니다. 같은 파일이 어제는 통과하고
 * 오늘은 막혔을 때 그 사이를 설명할 근거가 없습니다.
 *
 * <p><b>기록 위치가 설계의 핵심입니다.</b> 컨트롤러가 아니라 정책을 실제로 바꾸는 트랜잭션
 * 안에서 씁니다. 밖에서 쓰면 정책은 바뀌었는데 이력만 빠지는 경로가 생깁니다.
 */
@AutoConfigureMockMvc
@DisplayName("정책 변경 이력")
class PolicyChangeLogTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
		jdbc.update("DELETE FROM policy_change_log");
	}

	private List<Map<String, Object>> log() {
		return jdbc.queryForList("SELECT * FROM policy_change_log ORDER BY id");
	}

	private void toggle(String name, boolean blocked) throws Exception {
		mockMvc.perform(patch("/api/extensions/fixed/{name}", name)
				.header("X-Admin-Token", ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"blocked\": %s}".formatted(blocked)))
			.andExpect(status().isOk());
	}

	@Nested
	@DisplayName("변경을 남긴다")
	class Records {

		@Test
		@DisplayName("고정 확장자를 켜면 앞뒤 상태와 함께 남는다")
		void fixedBlock() throws Exception {
			toggle("exe", true);

			assertThat(log()).singleElement().satisfies(row -> {
				assertThat(row.get("action")).isEqualTo("FIXED_BLOCK");
				assertThat(row.get("extension_name")).isEqualTo("exe");
				assertThat(row.get("before_blocked")).isEqualTo(false);
				assertThat(row.get("after_blocked")).isEqualTo(true);
			});
		}

		@Test
		@DisplayName("해제도 남는다")
		void fixedUnblock() throws Exception {
			toggle("exe", true);
			toggle("exe", false);

			assertThat(log()).hasSize(2);
			assertThat(log().get(1).get("action")).isEqualTo("FIXED_UNBLOCK");
		}

		/**
		 * 토글은 멱등입니다. 같은 값으로 다시 부르면 상태가 바뀌지 않으므로 남길 변경이 없습니다.
		 * 이것을 남기면 "언제 무엇이 바뀌었나" 를 묻는 조회가 바뀌지 않은 행으로 오염됩니다.
		 */
		@Test
		@DisplayName("같은 값으로 다시 토글하면 남기지 않는다")
		void idempotentToggleIsNotRecorded() throws Exception {
			toggle("exe", true);
			toggle("exe", true);

			assertThat(log()).hasSize(1);
		}

		@Test
		@DisplayName("커스텀 추가·삭제가 남는다")
		void customAddAndDelete() throws Exception {
			mockMvc.perform(post("/api/extensions/custom")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\": \"sh\"}"))
				.andExpect(status().isCreated());
			mockMvc.perform(delete("/api/extensions/custom/{name}", "sh")
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());

			assertThat(log()).hasSize(2);
			assertThat(log().get(0).get("action")).isEqualTo("CUSTOM_ADD");
			assertThat(log().get(1).get("action")).isEqualTo("CUSTOM_DELETE");
			assertThat(log().get(0).get("before_blocked")).isNull();
		}

		/** 정규화된 값이 남아야 합니다. 입력한 대로 남기면 같은 확장자가 여러 값으로 갈라집니다. */
		@Test
		@DisplayName("정규화된 이름으로 남는다")
		void normalizedName() throws Exception {
			mockMvc.perform(post("/api/extensions/custom")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\": \".SH\"}"))
				.andExpect(status().isCreated());

			assertThat(log()).singleElement()
				.extracting(row -> row.get("extension_name")).isEqualTo("sh");
		}
	}

	@Nested
	@DisplayName("실패한 요청은 남기지 않는다")
	class DoesNotRecord {

		@Test
		@DisplayName("없는 확장자 토글은 이력을 만들지 않는다")
		void unknownExtension() throws Exception {
			mockMvc.perform(patch("/api/extensions/fixed/{name}", "nope")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\": true}"))
				.andExpect(status().isNotFound());

			assertThat(log()).isEmpty();
		}

		/** 토큰이 없으면 정책도 안 바뀌고 이력도 없어야 합니다. */
		@Test
		@DisplayName("토큰 없는 요청은 이력을 만들지 않는다")
		void withoutToken() throws Exception {
			mockMvc.perform(patch("/api/extensions/fixed/{name}", "exe")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"blocked\": true}"))
				.andExpect(status().isUnauthorized());

			assertThat(log()).isEmpty();
		}

		/**
		 * ★ 정책 변경과 이력이 <b>같은 트랜잭션</b>이어야 합니다. 커스텀 추가가 중복으로
		 * 거부되면 이력도 남으면 안 됩니다.
		 */
		@Test
		@DisplayName("중복 추가로 거부되면 이력도 남지 않는다")
		void duplicateRejected() throws Exception {
			mockMvc.perform(post("/api/extensions/custom")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\": \"sh\"}"))
				.andExpect(status().isCreated());
			mockMvc.perform(post("/api/extensions/custom")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\": \"sh\"}"))
				.andExpect(status().isConflict());

			assertThat(log()).hasSize(1);
		}
	}

	/**
	 * 이력의 가치는 "고쳐지지 않았다" 는 신뢰에서 나옵니다. {@code upload_audit} 과 같은 원칙입니다.
	 */
	@Nested
	@DisplayName("이력은 고쳐 쓸 수 없다")
	class AppendOnly {

		@Test
		@DisplayName("UPDATE 가 거부된다")
		void updateRejected() throws Exception {
			toggle("exe", true);

			assertThatThrownBy(() ->
				jdbc.update("UPDATE policy_change_log SET action = 'CUSTOM_ADD'"))
				.hasMessageContaining("append-only");
		}

		@Test
		@DisplayName("TRUNCATE 가 거부된다")
		void truncateRejected() {
			assertThatThrownBy(() -> jdbc.execute("TRUNCATE policy_change_log"))
				.hasMessageContaining("cannot be truncated");
		}

		/** 바뀌지 않은 토글은 행 자체가 성립하지 않아야 합니다. DB 가 막습니다. */
		@Test
		@DisplayName("앞뒤 상태가 같은 토글 행은 제약이 거부한다")
		void sameStateTransitionRejected() {
			assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO policy_change_log (action, extension_name, before_blocked, after_blocked)
				VALUES ('FIXED_BLOCK', 'exe', TRUE, TRUE)
				"""))
				.isInstanceOf(DataIntegrityViolationException.class);
		}
	}
}

package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 활동 로그. (GET /api/audit)
 *
 * <p>정책 변경과 업로드 판정을 <b>한 줄기로</b> 내려줍니다. 화면에서는 둘이 섞여 시간순으로
 * 보여야 하는데, 앱에서 합치면 양쪽을 넉넉히 가져와 자르는 낭비가 생깁니다. DB 가 합치고
 * 정렬하고 자릅니다.
 *
 * <p><b>이 경로는 읽기지만 공개가 아닙니다.</b> 관리 화면의 로그이고, 다른 사용자가 올린
 * 파일명과 차단 이력이 담깁니다. 지금까지 "읽기는 공개, 쓰기는 토큰" 이었으므로 <b>그 규칙에
 * 예외를 만드는</b> 셈입니다. 예외를 만든 이상 그것이 실제로 막히는지가 이 테스트의 첫 항목입니다.
 *
 * <p>요청자 주소를 함께 내려줍니다. 감사가 답해야 하는 것은 "무엇이 왜" 만이 아니라 "누가"
 * 이기도 한데, 지금은 관리 토큰이 하나뿐이라 계정으로 관리자를 구분할 수 없습니다. 구분에
 * 쓸 수 있는 유일한 신호가 주소입니다.
 */
@AutoConfigureMockMvc
@DisplayName("활동 로그")
class AuditControllerTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
		jdbc.update("DELETE FROM upload_audit");
		jdbc.update("DELETE FROM policy_change_log");
	}

	private void toggle(String name, boolean blocked) throws Exception {
		mockMvc.perform(patch("/api/extensions/fixed/{name}", name)
				.header("X-Admin-Token", ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"blocked\": %s}".formatted(blocked)))
			.andExpect(status().isOk());
	}

	private void addCustom(String name) throws Exception {
		mockMvc.perform(post("/api/extensions/custom")
				.header("X-Admin-Token", ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"%s\"}".formatted(name)))
			.andExpect(status().isCreated());
	}

	private void upload(String filename) throws Exception {
		mockMvc.perform(multipart("/api/files").file(new MockMultipartFile(
			"file", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE,
			"hello".getBytes(StandardCharsets.UTF_8))));
	}

	private JsonNode entries() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/audit").header("X-Admin-Token", ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andReturn();
		return json.readTree(result.getResponse().getContentAsString()).get("entries");
	}

	@Nested
	@DisplayName("보호된다")
	class Protected {

		/**
		 * ★ 필터가 안전한 메서드(GET·HEAD·OPTIONS)를 <b>경로보다 먼저</b> 통과시키고 있었습니다.
		 * 경로 패턴만 추가하면 이 요청은 필터를 그냥 지나갑니다.
		 */
		@Test
		@DisplayName("토큰 없는 GET 은 401 이다")
		void requiresToken() throws Exception {
			mockMvc.perform(get("/api/audit"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_TOKEN_REQUIRED"));
		}

		@Test
		@DisplayName("틀린 토큰도 401 이다")
		void rejectsWrongToken() throws Exception {
			mockMvc.perform(get("/api/audit").header("X-Admin-Token", "x".repeat(48)))
				.andExpect(status().isUnauthorized());
		}

		// 인코딩 변형은 MockMvc 로 답할 수 없습니다. 컨테이너를 거치지 않아 디코딩이 다르고,
		// 실제로 이 경로를 MockMvc 에 보내면 필터에 닿기도 전에 400 이 납니다. 통과해도 실패해도
		// 서버가 무엇을 했는지 알 수 없으므로 실 톰캣을 쓰는 AdminTokenPathBypassTest 에 두었습니다.

		/** 이 예외 때문에 다른 읽기까지 막히면 안 됩니다. */
		@Test
		@DisplayName("정책 조회와 파일 목록은 토큰 없이 그대로 열려 있다")
		void otherReadsStayPublic() throws Exception {
			mockMvc.perform(get("/api/extensions")).andExpect(status().isOk());
			mockMvc.perform(get("/api/files")).andExpect(status().isOk());
		}

		/**
		 * "누가" 를 답할 수 있어야 합니다. 계정 체계가 들어오기 전까지 그 자리를 주소가 대신합니다.
		 */
		@Test
		@DisplayName("요청자 주소가 함께 내려온다")
		void carriesClientAddress() throws Exception {
			toggle("exe", true);

			assertThat(entries().get(0).has("clientIp")).isTrue();
		}
	}

	@Nested
	@DisplayName("두 기록을 한 줄기로 내려준다")
	class Merged {

		@Test
		@DisplayName("정책 변경이 보인다")
		void showsPolicyChange() throws Exception {
			toggle("exe", true);

			JsonNode e = entries();
			assertThat(e).hasSize(1);
			assertThat(e.get(0).get("kind").asText()).isEqualTo("POLICY");
			assertThat(e.get(0).get("action").asText()).isEqualTo("FIXED_BLOCK");
			assertThat(e.get(0).get("target").asText()).isEqualTo("exe");
		}

		@Test
		@DisplayName("업로드 성공과 차단이 모두 보인다")
		void showsUploads() throws Exception {
			toggle("exe", true);
			upload("normal.txt");
			upload("blocked.exe");

			List<String> kinds = new java.util.ArrayList<>();
			List<String> actions = new java.util.ArrayList<>();
			entries().forEach(n -> {
				kinds.add(n.get("kind").asText());
				actions.add(n.get("action").asText());
			});
			assertThat(kinds).contains("UPLOAD", "POLICY");
			assertThat(actions).contains("ALLOWED", "BLOCKED", "FIXED_BLOCK");
		}

		/** 차단은 이유가 함께 보여야 합니다. 로그가 "막혔다" 만 말하면 아무것도 답하지 못합니다. */
		@Test
		@DisplayName("차단된 업로드는 사유가 함께 나온다")
		void blockedCarriesReason() throws Exception {
			toggle("exe", true);
			upload("blocked.exe");

			JsonNode blocked = null;
			for (JsonNode n : entries()) {
				if ("BLOCKED".equals(n.get("action").asText())) blocked = n;
			}
			assertThat(blocked).isNotNull();
			assertThat(blocked.get("detail").asText()).contains("FILE_BLOCKED_EXTENSION");
		}

		/**
		 * 정책 줄의 detail 은 <b>바뀐 뒤의 상태</b>를 말해야 합니다. 커스텀 추가·삭제만
		 * 동작 이름을 되풀이하면, 판정 칸이 줄마다 다른 것을 뜻하게 되어 훑어 읽을 수 없습니다.
		 */
		@Test
		@DisplayName("커스텀 추가·삭제도 바뀐 뒤의 차단 상태를 말한다")
		void customRowsDescribeResultingState() throws Exception {
			addCustom("sh");

			JsonNode e = entries();
			assertThat(e.get(0).get("action").asText()).isEqualTo("CUSTOM_ADD");
			assertThat(e.get(0).get("detail").asText()).isEqualTo("차단 켜짐");
		}

		@Test
		@DisplayName("최신순으로 내려온다")
		void newestFirst() throws Exception {
			toggle("exe", true);
			toggle("js", true);

			JsonNode e = entries();
			assertThat(e.get(0).get("target").asText()).isEqualTo("js");
			assertThat(e.get(1).get("target").asText()).isEqualTo("exe");
		}

		@Test
		@DisplayName("limit 으로 개수를 줄일 수 있다")
		void respectsLimit() throws Exception {
			toggle("exe", true);
			toggle("js", true);
			toggle("bat", true);

			MvcResult r = mockMvc.perform(get("/api/audit").param("limit", "2").header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isOk()).andReturn();
			assertThat(json.readTree(r.getResponse().getContentAsString()).get("entries")).hasSize(2);
		}

		/** 상한이 없으면 요청 하나가 전체 감사 테이블을 끌어옵니다. */
		@Test
		@DisplayName("limit 이 과하면 상한으로 잘린다")
		void capsLimit() throws Exception {
			toggle("exe", true);

			mockMvc.perform(get("/api/audit").param("limit", "100000").header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isOk());
		}

		@Test
		@DisplayName("기록이 없으면 빈 목록이다")
		void emptyWhenNothing() throws Exception {
			assertThat(entries()).isEmpty();
		}
	}
}

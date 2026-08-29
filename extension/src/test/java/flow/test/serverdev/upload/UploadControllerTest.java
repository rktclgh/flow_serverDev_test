package flow.test.serverdev.upload;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

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

import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 업로드 API 의 HTTP 계약. (SPEC §7.5, §21.7)
 *
 * <p>판정 규칙은 {@code UploadValidatorTest} 가, 감사·저장은 {@code UploadServiceTest} 가 본다.
 * 여기서 확인하는 것은 <b>요청이 잘못 만들어졌을 때</b>다 — 파트가 없거나, 이름이 다르거나,
 * 파일이 아니거나, 둘 이상일 때. 이 경로들은 판정에 도달하지도 못하므로 다른 테스트가 볼 수 없다.
 */
@AutoConfigureMockMvc
@DisplayName("업로드 API")
class UploadControllerTest extends IntegrationTest {

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

	private static MockMultipartFile file(String partName, String filename) {
		return new MockMultipartFile(partName, filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, BODY);
	}

	private long auditCount() {
		return jdbc.queryForObject("SELECT count(*) FROM upload_audit", Long.class);
	}

	@Nested
	@DisplayName("성공")
	class Success {

		@Test
		@DisplayName("201 과 함께 fileId·원본명·크기를 돌려준다")
		void created() throws Exception {
			mockMvc.perform(multipart("/api/files").file(file("file", "report.pdf")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.fileId").isNotEmpty())
				.andExpect(jsonPath("$.originalFilename").value("report.pdf"))
				.andExpect(jsonPath("$.size").value(BODY.length));
		}
	}

	@Nested
	@DisplayName("정책 거부")
	class PolicyRejection {

		@Test
		@DisplayName("차단 확장자는 422 와 함께 무엇이 왜 걸렸는지 알려준다")
		void blocked() throws Exception {
			jdbc.update("UPDATE blocked_extension SET is_blocked = TRUE WHERE name = ?", "exe");

			mockMvc.perform(multipart("/api/files").file(file("file", "setup.exe")))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("FILE_BLOCKED_EXTENSION"))
				.andExpect(jsonPath("$.detail.blockedExtension").value("exe"))
				.andExpect(jsonPath("$.detail.policyType").value("FIXED"));
		}
	}

	@Nested
	@DisplayName("파트 계약 — 판정에 도달하지 못하는 요청들")
	class PartContract {

		@Test
		@DisplayName("파일 파트가 하나도 없으면 FILE_REQUIRED")
		void noFilePart() throws Exception {
			mockMvc.perform(multipart("/api/files"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FILE_REQUIRED"));
		}

		@Test
		@DisplayName("파일은 있는데 파트 이름이 file 이 아니면 FILE_REQUIRED")
		void wrongPartName() throws Exception {
			mockMvc.perform(multipart("/api/files").file(file("avatar", "report.pdf")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FILE_REQUIRED"));
		}

		@Test
		@DisplayName("file 이 파일이 아니라 텍스트 파트면 FILE_REQUIRED")
		void textPartNamedFile() throws Exception {
			mockMvc.perform(multipart("/api/files")
					.file(new MockMultipartFile("file", null, MediaType.TEXT_PLAIN_VALUE, BODY)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FILE_REQUIRED"));
		}

		@Test
		@DisplayName("파일이 둘 이상이면 FILE_COUNT_EXCEEDED")
		void twoFiles() throws Exception {
			mockMvc.perform(multipart("/api/files")
					.file(file("file", "a.pdf"))
					.file(file("second", "b.pdf")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FILE_COUNT_EXCEEDED"));
		}

		/**
		 * SPEC §21.2 — 감사는 "정책 판정에 도달한 요청" 만 기록한다. 파트가 없으면 적을 파일명조차
		 * 없고({@code original_filename} 은 NOT NULL), 형식 오류까지 담으면 정작 필요한 질의가
		 * 잡음에 묻힌다.
		 */
		@Test
		@DisplayName("★ 파트 계약 위반은 감사하지 않는다 — 판정에 도달하지 않았다")
		void contractViolationIsNotAudited() throws Exception {
			mockMvc.perform(multipart("/api/files")).andExpect(status().isBadRequest());
			mockMvc.perform(multipart("/api/files").file(file("avatar", "a.pdf")))
				.andExpect(status().isBadRequest());

			org.assertj.core.api.Assertions.assertThat(auditCount()).isZero();
		}
	}

	@Nested
	@DisplayName("요청 형식")
	class RequestShape {

		@Test
		@DisplayName("multipart 가 아니면 415")
		void notMultipart() throws Exception {
			mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
					.post("/api/files")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isUnsupportedMediaType());
		}
	}
}

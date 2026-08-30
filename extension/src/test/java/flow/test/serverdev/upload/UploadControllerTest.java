package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;
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

			assertThat(auditCount()).isZero();
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
	/**
	 * ★ 관측 컬럼이 정상 업로드를 막고 있었다.
	 *
	 * <p>{@code note} 는 스키마가 <b>신호 이름</b>을 담으라고 만든 40자 컬럼이다
	 * (V2 주석의 예시가 {@code SUSPICIOUS_MIDDLE_SEGMENT} 다). 그런데 구현이 중간
	 * 세그먼트 <b>목록</b>을 이어 붙여 넣고 있었다. 이어 붙인 길이가 25자를 넘으면
	 * INSERT 가 터져 <b>차단 대상도 아닌 평범한 파일이 500</b> 이 된다.
	 *
	 * <pre>
	 *   backup.database.production.snapshot.sql
	 *     note = "middleSegments=database,production,snapshot"  (43자 &gt; 40)
	 *     ERROR: value too long for type character varying(40)
	 * </pre>
	 *
	 * <p>폭을 늘리는 것은 증상만 만지는 쪽이다. 세그먼트 이름은 {@code original_filename}
	 * 에 이미 통째로 들어 있어 중복이고, 컬럼의 용도는 애초에 신호였다.
	 * <b>구조적으로 상한 안에 들어오는 신호 이름</b>만 남긴다.
	 */
	@Nested
	@DisplayName("중간 세그먼트 관측")
	class MiddleSegmentNote {

		@Test
		@DisplayName("세그먼트가 길어도 500 이 아니라 201 이다")
		void longSegmentsDoNotBreakUpload() throws Exception {
			mockMvc.perform(multipart("/api/files")
					.file(file("file", "backup.database.production.snapshot.sql")))
				.andExpect(status().isCreated());

			assertThat(auditCount()).isEqualTo(1);
		}

		/** 차단 목록에 있는 확장자가 중간에 숨어 있을 때만 신호를 남긴다. */
		@Test
		@DisplayName("중간에 차단 확장자가 숨어 있으면 신호를 남긴다")
		void marksSuspiciousMiddleSegment() throws Exception {
			jdbc.update("UPDATE blocked_extension SET is_blocked = TRUE WHERE name = 'exe'");

			mockMvc.perform(multipart("/api/files").file(file("file", "invoice.exe.txt")))
				.andExpect(status().isCreated());

			assertThat(noteOf("invoice.exe.txt")).isEqualTo("SUSPICIOUS_MIDDLE_SEGMENT");
		}

		/**
		 * 점이 여럿이라는 것만으로는 신호가 아니다. {@code archive.tar.gz} 는 정상적인 이름이고,
		 * 여기에 신호를 남기면 진짜 위장 시도가 묻힌다.
		 */
		@Test
		@DisplayName("차단 목록에 없는 중간 세그먼트는 신호가 아니다")
		void plainMultiDotNameHasNoNote() throws Exception {
			mockMvc.perform(multipart("/api/files").file(file("file", "archive.tar.gz")))
				.andExpect(status().isCreated());

			assertThat(noteOf("archive.tar.gz")).isNull();
		}

		/** 신호는 이름이므로 길이가 파일명에 따라 변하지 않는다. */
		@Test
		@DisplayName("신호는 컬럼 상한 안에서 길이가 고정이다")
		void noteFitsColumn() throws Exception {
			jdbc.update("UPDATE blocked_extension SET is_blocked = TRUE WHERE name = 'exe'");

			mockMvc.perform(multipart("/api/files")
					.file(file("file", "a.exe.database.production.snapshot.txt")))
				.andExpect(status().isCreated());

			assertThat(noteOf("a.exe.database.production.snapshot.txt"))
				.isEqualTo("SUSPICIOUS_MIDDLE_SEGMENT")
				.hasSizeLessThanOrEqualTo(40);
		}

		private String noteOf(String filename) {
			return jdbc.queryForObject(
				"SELECT note FROM upload_audit WHERE original_filename = ?", String.class, filename);
		}
	}
}

package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * 목록 조회. (GET /api/files)
 *
 * <p>업로드는 되는데 무엇이 올라갔는지 볼 방법이 없었다. 이 엔드포인트가 그것을 답한다.
 *
 * <p><b>보여주는 것과 감추는 것이 함께 계약이다.</b> 같은 행에 {@code client_ip}·
 * {@code reason_code}·{@code stored_key} 가 있지만 공개 목록에 실리면 안 된다.
 * 차단 기록도 마찬가지다 — "누가 무엇을 올리려다 막혔나" 는 관리자의 정보다.
 */
@AutoConfigureMockMvc
@DisplayName("파일 목록")
class FileListControllerTest extends IntegrationTest {

	private static final byte[] BODY = "hello list".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
		jdbc.update("DELETE FROM upload_audit");
	}

	/** 업로드 API 를 그대로 거친다. 조립한 픽스처가 아니라 실제로 만들어진 행을 본다. */
	private UUID upload(String filename) throws Exception {
		MvcResult result = mockMvc.perform(multipart("/api/files")
				.file(new MockMultipartFile("file", filename,
					MediaType.APPLICATION_OCTET_STREAM_VALUE, BODY)))
			.andExpect(status().isCreated())
			.andReturn();

		return UUID.fromString(
			json.readTree(result.getResponse().getContentAsString()).get("fileId").asText());
	}

	/** 시각을 직접 지정한다. 같은 트랜잭션의 {@code now()} 는 값이 같아 순서를 검증할 수 없다. */
	private UUID insertAllowed(String filename, String occurredAt) {
		UUID fileId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO upload_audit (occurred_at, original_filename, size_bytes, result, stored_key, file_id)
			VALUES (?::timestamptz, ?, ?, 'ALLOWED', ?, ?::uuid)
			""", occurredAt, filename, (long) BODY.length,
			"2026/08/29/" + fileId, fileId.toString());
		return fileId;
	}

	private JsonNode listFiles() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/files"))
			.andExpect(status().isOk())
			.andReturn();
		return json.readTree(result.getResponse().getContentAsString()).get("files");
	}

	private List<String> listedNames() throws Exception {
		List<String> names = new ArrayList<>();
		listFiles().forEach(node -> names.add(node.get("originalFilename").asText()));
		return names;
	}

	@Nested
	@DisplayName("무엇을 보여주는가")
	class Content {

		@Test
		@DisplayName("올린 파일이 목록에 나온다 — 식별자·이름·크기·시각")
		void showsUploadedFile() throws Exception {
			UUID fileId = upload("report.pdf");

			mockMvc.perform(get("/api/files"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.files.length()").value(1))
				.andExpect(jsonPath("$.files[0].fileId").value(fileId.toString()))
				.andExpect(jsonPath("$.files[0].originalFilename").value("report.pdf"))
				.andExpect(jsonPath("$.files[0].size").value(BODY.length))
				.andExpect(jsonPath("$.files[0].uploadedAt").exists());
		}

		/**
		 * 시각은 <b>오프셋을 달고</b> 나가야 한다. 없으면 클라이언트가 자기 시간대로 읽어
		 * 목록의 시각이 사람마다 달라진다.
		 */
		@Test
		@DisplayName("시각은 오프셋이 있는 ISO-8601 이다")
		void isoOffsetTimestamp() throws Exception {
			upload("report.pdf");

			String uploadedAt = listFiles().get(0).get("uploadedAt").asText();

			assertThat(uploadedAt)
				.as("배열·에폭 초가 아니라 오프셋을 포함한 문자열이어야 한다")
				.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
			assertThat(OffsetDateTime.parse(uploadedAt)).isNotNull();
		}

		@Test
		@DisplayName("최신순으로 나온다")
		void newestFirst() throws Exception {
			insertAllowed("old.pdf", "2026-08-27 10:00:00+09");
			insertAllowed("newest.pdf", "2026-08-29 10:00:00+09");
			insertAllowed("middle.pdf", "2026-08-28 10:00:00+09");

			assertThat(listedNames()).containsExactly("newest.pdf", "middle.pdf", "old.pdf");
		}

		/**
		 * ★ 상한이 없으면 행이 쌓일수록 응답이 커진다. 페이지네이션은 과제 범위 밖이지만
		 * "전부 내보낸다" 는 선택은 시간이 지나면 반드시 문제가 된다.
		 */
		@Test
		@DisplayName("최대 100개까지만 내보낸다 — 최신 100개")
		void capsAtHundred() throws Exception {
			for (int i = 0; i < 105; i++) {
				insertAllowed("file-%03d.pdf".formatted(i),
					"2026-08-%02d %02d:00:00+09".formatted(1 + i / 24, i % 24));
			}

			List<String> names = listedNames();

			assertThat(names).hasSize(100);
			assertThat(names).first().isEqualTo("file-104.pdf");
		}

		/** 공개 엔드포인트다 — 과제가 "누구나 접속 가능" 을 요구한다. */
		@Test
		@DisplayName("관리 토큰 없이 조회할 수 있다")
		void isPublic() throws Exception {
			upload("report.pdf");

			mockMvc.perform(get("/api/files"))
				.andExpect(status().isOk());
		}
	}

	@Nested
	@DisplayName("무엇을 감추는가")
	class Hidden {

		@Test
		@DisplayName("차단된 기록은 목록에 없다")
		void blockedIsHidden() throws Exception {
			jdbc.update("""
				INSERT INTO upload_audit (original_filename, result, reason_code)
				VALUES ('setup.exe', 'BLOCKED', 'FILE_BLOCKED_EXTENSION')
				""");

			assertThat(listedNames()).isEmpty();
		}

		/** 확정되지 않은 업로드는 아직 "올라간 파일" 이 아니다. 다운로드도 404 다(SPEC §7.6). */
		@Test
		@DisplayName("PENDING 은 목록에 없다")
		void pendingIsHidden() throws Exception {
			UUID fileId = UUID.randomUUID();
			jdbc.update("""
				INSERT INTO upload_audit (original_filename, result, stored_key, file_id)
				VALUES ('half.pdf', 'PENDING', ?, ?::uuid)
				""", "2026/08/29/" + fileId, fileId.toString());

			assertThat(listedNames()).isEmpty();
		}

		@Test
		@DisplayName("ERROR 는 목록에 없다")
		void errorIsHidden() throws Exception {
			UUID fileId = UUID.randomUUID();
			jdbc.update("""
				INSERT INTO upload_audit (original_filename, result, reason_code, stored_key, file_id)
				VALUES ('broken.pdf', 'ERROR', 'STORAGE_UNAVAILABLE', ?, ?::uuid)
				""", "2026/08/29/" + fileId, fileId.toString());

			assertThat(listedNames()).isEmpty();
		}

		/**
		 * ★ 삭제된 파일은 목록에서 사라진다. 행은 남아 있지만 객체가 없으므로,
		 * 여기 보이면 다운로드가 404 인 항목을 사용자에게 내미는 셈이 된다.
		 */
		@Test
		@DisplayName("삭제된 파일은 목록에 없다 — 행은 남아 있어도")
		void deletedIsHidden() throws Exception {
			UUID kept = insertAllowed("kept.pdf", "2026-08-29 10:00:00+09");
			UUID removed = insertAllowed("removed.pdf", "2026-08-29 11:00:00+09");
			jdbc.update("UPDATE upload_audit SET deleted_at = now() WHERE file_id = ?::uuid",
				removed.toString());

			assertThat(listedNames()).containsExactly("kept.pdf");
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM upload_audit WHERE file_id IN (?::uuid, ?::uuid)",
				Long.class, kept.toString(), removed.toString()))
				.as("목록에서 사라졌다고 기록까지 사라지면 이 서비스의 존재 이유가 사라진다")
				.isEqualTo(2L);
		}

		/**
		 * 감사 행에는 공개하면 안 되는 값이 함께 있다. 엔티티를 그대로 직렬화하면
		 * 그것들이 통째로 나간다 — 그래서 별도 record 로 담는다.
		 */
		@Test
		@DisplayName("관리자의 정보는 내보내지 않는다 — client_ip · stored_key · reason_code")
		void hidesAdminFields() throws Exception {
			upload("report.pdf");

			String body = mockMvc.perform(get("/api/files"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

			assertThat(body)
				.doesNotContain("clientIp").doesNotContain("client_ip")
				.doesNotContain("storedKey").doesNotContain("stored_key")
				.doesNotContain("reasonCode").doesNotContain("reason_code")
				.doesNotContain("deletedAt").doesNotContain("deleted_at");
		}
	}
}

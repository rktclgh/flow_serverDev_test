package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import flow.test.serverdev.storage.MinioObjectStorage;
import flow.test.serverdev.storage.ObjectStorage;
import flow.test.serverdev.storage.StorageException;
import flow.test.serverdev.storage.StorageKey;
import flow.test.serverdev.storage.StorageKeyGenerator;
import flow.test.serverdev.storage.StorageObjectNotFoundException;
import flow.test.serverdev.storage.StorageProperties;
import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

import io.minio.MinioClient;

/**
 * 삭제. (DELETE /api/files/&#123;fileId&#125;)
 *
 * <p><b>객체는 지우고 기록은 남긴다.</b> 감사 행을 지우면 "무엇이 왜 올라갔는가" 를 함께
 * 잃는다 — 이 서비스의 존재 이유가 사라진다. 그래서 지우는 것은 오브젝트 스토리지의
 * 객체뿐이고, 기록에는 {@code deleted_at} 이라는 사실이 하나 더 붙는다.
 *
 * <p><b>순서가 설계의 핵심이다.</b> 조건부 UPDATE 로 소유권을 먼저 얻고, 그 UPDATE 가
 * 실제로 1행을 바꿨을 때만 객체를 지운다. 반대로 하면 객체는 없는데 {@code deleted_at} 은
 * NULL 인 행이 남아, <b>목록에는 보이는데 다운로드는 404</b> 가 된다.
 * 근거는 SPEC §21.6 의 스위퍼 경합과 같다.
 *
 * <p><b>삭제만 관리 토큰을 요구한다.</b> 업로드·조회·다운로드는 공개지만 삭제는 파괴적이다.
 * 공개로 두면 누구나 남의 파일을 지울 수 있으므로 정책 변경과 같은 등급으로 다룬다.
 */
@AutoConfigureMockMvc
@Import(FileDeleteControllerTest.ObservingStorage.class)
@DisplayName("파일 삭제")
class FileDeleteControllerTest extends IntegrationTest {

	private static final byte[] BODY = "hello delete".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectStorage storage;

	@Autowired
	private StorageKeyGenerator keyGenerator;

	private final ObjectMapper json = new ObjectMapper();

	private DeletionOrderObserver observer() {
		return (DeletionOrderObserver) storage;
	}

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
		jdbc.update("DELETE FROM upload_audit");
		observer().reset();
	}

	private UUID upload(String filename) throws Exception {
		MvcResult result = mockMvc.perform(multipart("/api/files")
				.file(new MockMultipartFile("file", filename,
					MediaType.APPLICATION_OCTET_STREAM_VALUE, BODY)))
			.andExpect(status().isCreated())
			.andReturn();

		return UUID.fromString(
			json.readTree(result.getResponse().getContentAsString()).get("fileId").asText());
	}

	/**
	 * 객체는 실제로 저장하고 행만 다른 상태로 심는다.
	 *
	 * <p>객체 없이 행만 만들면 "상태가 아니라 객체가 없어서" 실패하는 경우와 구분되지 않는다.
	 */
	private UUID storeWithResult(String filename, String result, String reasonCode) {
		StorageKey key = keyGenerator.generate();
		storage.store(key, new ByteArrayInputStream(BODY), BODY.length);
		jdbc.update("""
			INSERT INTO upload_audit (original_filename, size_bytes, result, reason_code, stored_key, file_id)
			VALUES (?, ?, ?, ?, ?, ?::uuid)
			""", filename, (long) BODY.length, result, reasonCode, key.value(), key.fileId().toString());
		return key.fileId();
	}

	private String storedKeyOf(UUID fileId) {
		return jdbc.queryForObject("SELECT stored_key FROM upload_audit WHERE file_id = ?::uuid",
			String.class, fileId.toString());
	}

	private List<String> listedNames() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/files"))
			.andExpect(status().isOk())
			.andReturn();
		List<String> names = new ArrayList<>();
		json.readTree(result.getResponse().getContentAsString()).get("files")
			.forEach(node -> names.add(node.get("originalFilename").asText()));
		return names;
	}


	/** 여러 건을 한 번에 지우는 요청 본문. */
	private String idsBody(UUID... fileIds) throws Exception {
		List<String> values = new ArrayList<>();
		for (UUID fileId : fileIds) {
			values.add(fileId.toString());
		}
		return json.writeValueAsString(Map.of("fileIds", values));
	}

	/**
	 * 여러 건을 한 번에 지운다. (DELETE /api/files)
	 *
	 * <p><b>단건과 다른 점은 실패의 모양이다.</b> 목록은 낡을 수 있다 — 다른 탭에서 이미
	 * 지웠거나, 스위퍼가 걷어간 뒤일 수 있다. 그때 요청 전체를 거부하면 사용자는 새로고침 후
	 * 다시 고르는 일을 반복하게 된다. 그래서 <b>건별로 답한다</b>: 지운 것은 지우고,
	 * 없던 것은 없었다고 알린다.
	 *
	 * <p>순서 계약은 단건과 <b>같다</b>. 소유권을 먼저 얻고 그다음에 객체를 지운다.
	 * 여러 건이라고 해서 이 순서가 느슨해지면 안 된다.
	 */
	@Nested
	@DisplayName("여러 건을 한 번에 지운다")
	class DeletesMany {

		@Test
		@DisplayName("두 건을 지우면 둘 다 목록에서 사라진다")
		void removesAll() throws Exception {
			UUID first = upload("first.pdf");
			UUID second = upload("second.pdf");

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(first, second)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deleted.length()").value(2))
				.andExpect(jsonPath("$.notFound.length()").value(0));

			assertThat(listedNames()).isEmpty();
		}

		/** 목록에서 빠지는 것만으로는 부족하다. 바이트가 실제로 없어져야 지운 것이다. */
		@Test
		@DisplayName("객체가 실제로 전부 없어진다")
		void removesEveryObject() throws Exception {
			UUID first = upload("first.pdf");
			UUID second = upload("second.pdf");
			String firstKey = storedKeyOf(first);
			String secondKey = storedKeyOf(second);

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(first, second)))
				.andExpect(status().isOk());

			assertThatThrownBy(() -> storage.load(firstKey))
				.isInstanceOf(StorageObjectNotFoundException.class);
			assertThatThrownBy(() -> storage.load(secondKey))
				.isInstanceOf(StorageObjectNotFoundException.class);
		}

		/**
		 * ★ 낡은 목록이 나머지를 막지 않는다.
		 *
		 * <p>이 한 건 때문에 전체가 거부되면, 사용자는 새로고침하고 다시 고르는 일을
		 * 반복하게 된다. 지울 수 있는 것은 지우고 나머지를 알려준다.
		 */
		@Test
		@DisplayName("없는 id 가 섞여 있어도 있는 것은 지운다")
		void deletesWhatExists() throws Exception {
			UUID existing = upload("keep-going.pdf");
			UUID missing = UUID.randomUUID();

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(existing, missing)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deleted[0]").value(existing.toString()))
				.andExpect(jsonPath("$.notFound[0]").value(missing.toString()));

			assertThat(listedNames()).isEmpty();
		}

		/**
		 * 같은 id 를 두 번 보내면 두 번째는 소유권을 얻지 못한다. 그것을 "없다" 로 답하면
		 * 한 파일이 <b>지웠음과 없음에 동시에</b> 나타난다 — 화면이 설명할 수 없는 상태다.
		 */
		@Test
		@DisplayName("같은 id 가 중복돼도 한 번만 처리한다")
		void deduplicates() throws Exception {
			UUID fileId = upload("once.pdf");

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(fileId, fileId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deleted.length()").value(1))
				.andExpect(jsonPath("$.notFound.length()").value(0));
		}

		/** 이미 지운 것을 다시 고른 경우. 두 번 지워지지 않는다. */
		@Test
		@DisplayName("이미 지운 파일은 없다고 답한다")
		void alreadyDeleted() throws Exception {
			UUID fileId = upload("gone.pdf");

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(fileId)))
				.andExpect(status().isOk());

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(fileId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deleted.length()").value(0))
				.andExpect(jsonPath("$.notFound[0]").value(fileId.toString()));
		}

		/** 단건과 같은 계약이다. 여러 건이라고 기록이 사라지면 안 된다. */
		@Test
		@DisplayName("기록은 전부 남는다")
		void keepsEveryAuditRow() throws Exception {
			UUID first = upload("first.pdf");
			UUID second = upload("second.pdf");

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(first, second)))
				.andExpect(status().isOk());

			Long rows = jdbc.queryForObject(
				"SELECT count(*) FROM upload_audit WHERE deleted_at IS NOT NULL", Long.class);
			assertThat(rows).isEqualTo(2L);
		}

		/**
		 * ★ 순서 계약. 여러 건에서도 소유권이 객체 삭제보다 앞선다.
		 *
		 * <p>반대로 하면 객체는 없는데 {@code deleted_at} 은 NULL 인 행이 남아
		 * 목록에는 보이는데 다운로드는 404 가 된다.
		 */
		@Test
		@DisplayName("건마다 소유권을 먼저 얻은 뒤 객체를 지운다")
		void claimsBeforeDeleting() throws Exception {
			UUID first = upload("first.pdf");
			UUID second = upload("second.pdf");
			String firstKey = storedKeyOf(first);
			String secondKey = storedKeyOf(second);

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(first, second)))
				.andExpect(status().isOk());

			assertThat(observer().rowWasMarkedWhenDeleted(firstKey)).isTrue();
			assertThat(observer().rowWasMarkedWhenDeleted(secondKey)).isTrue();
		}

		@Test
		@DisplayName("빈 목록은 거부한다")
		void rejectsEmpty() throws Exception {
			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"fileIds\": []}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		}

		/**
		 * 상한이 없으면 요청 하나가 임의로 많은 객체 삭제를 일으킨다. 화면에서 고를 수 있는
		 * 수를 훨씬 웃도는 값이므로 정상 사용에는 걸리지 않는다.
		 */
		@Test
		@DisplayName("상한(100)을 넘으면 거부한다")
		void rejectsTooMany() throws Exception {
			List<String> tooMany = new ArrayList<>();
			for (int i = 0; i < 101; i++) {
				tooMany.add(UUID.randomUUID().toString());
			}

			mockMvc.perform(delete("/api/files")
					.header("X-Admin-Token", ADMIN_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(Map.of("fileIds", tooMany))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		}

		/** 단건 삭제와 같은 등급이다. 컬렉션 경로라고 열려 있으면 안 된다. */
		@Test
		@DisplayName("토큰이 없으면 401 이고 아무것도 지우지 않는다")
		void requiresAdminToken() throws Exception {
			UUID fileId = upload("protected.pdf");

			mockMvc.perform(delete("/api/files")
					.contentType(MediaType.APPLICATION_JSON)
					.content(idsBody(fileId)))
				.andExpect(status().isUnauthorized());

			assertThat(listedNames()).containsExactly("protected.pdf");
		}
	}
	@Nested
	@DisplayName("지운다")
	class Deletes {

		@Test
		@DisplayName("204 를 주고 목록에서 사라진다")
		void removesFromList() throws Exception {
			UUID fileId = upload("report.pdf");

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());

			assertThat(listedNames()).isEmpty();
		}

		/** 목록에서 빠지는 것만으로는 부족하다. 실제로 바이트가 없어져야 지운 것이다. */
		@Test
		@DisplayName("오브젝트 스토리지의 객체가 실제로 없어진다")
		void removesObject() throws Exception {
			UUID fileId = upload("report.pdf");
			String key = storedKeyOf(fileId);

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());

			assertThatThrownBy(() -> storage.load(key))
				.isInstanceOf(StorageObjectNotFoundException.class);
		}

		/**
		 * ★ 이 서비스의 존재 이유가 걸린 항목이다. 행까지 지우면 "무엇이 왜 올라갔는가" 를
		 * 답할 수 없게 되고, "그런 일이 없었다" 와 구분되지 않는다.
		 */
		@Test
		@DisplayName("기록은 남는다 — 행과 파일명·저장 키가 그대로다")
		void keepsAuditRow() throws Exception {
			UUID fileId = upload("report.pdf");

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());

			Map<String, Object> row = jdbc.queryForMap(
				"SELECT result, original_filename, stored_key, deleted_at "
					+ "FROM upload_audit WHERE file_id = ?::uuid", fileId.toString());

			assertThat(row.get("result")).isEqualTo("ALLOWED");
			assertThat(row.get("original_filename")).isEqualTo("report.pdf");
			assertThat(row.get("stored_key")).as("무엇이 남았는지 추적할 수 있어야 한다").isNotNull();
			assertThat(row.get("deleted_at")).as("지웠다는 사실이 기록에 남는다").isNotNull();
		}

		/** 목록에서만 빼고 다운로드가 열려 있으면 지운 것이 아니다. */
		@Test
		@DisplayName("지운 파일은 다운로드도 404 다")
		void downloadIsGone() throws Exception {
			UUID fileId = upload("report.pdf");

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());

			mockMvc.perform(get("/api/files/{fileId}/content", fileId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
		}

		/**
		 * ★★ <b>소유권을 먼저 얻고, 얻었을 때만 객체를 지운다.</b> (SPEC §21.6 과 같은 근거)
		 *
		 * <p>순서를 뒤집으면 객체 삭제 뒤 행 갱신이 실패했을 때 {@code deleted_at} 이 NULL 인데
		 * 객체가 없는 행이 남는다 — 목록에는 보이는데 다운로드는 404 다. 겉으로 드러나는
		 * 응답은 정상 경로에서 같으므로, <b>객체를 지우는 순간의 DB 상태를 직접 본다.</b>
		 */
		@Test
		@DisplayName("★ 객체를 지우기 전에 행이 먼저 확정돼 있다")
		void claimsBeforeDeletingObject() throws Exception {
			UUID fileId = upload("report.pdf");
			String key = storedKeyOf(fileId);

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());

			assertThat(observer().deletedKeys()).containsExactly(key);
			assertThat(observer().rowWasMarkedWhenDeleted(key))
				.as("객체를 지우는 시점에 deleted_at 이 이미 커밋돼 있어야 한다. "
					+ "순서가 반대면 삭제된 객체를 가리키는 살아 있는 행이 남는다")
				.isTrue();
		}
	}

	/**
	 * 객체 삭제가 실패하면 어떻게 되는가. (외부 리뷰 CodeRabbit)
	 *
	 * <p>소유권을 먼저 얻는 순서라, 객체 삭제가 실패해도 <b>행은 이미 삭제로 확정</b>돼 있다.
	 * 그 상태에서 503 을 돌려주면 사용자에게 거짓말이 된다 — 파일은 이미 목록에서도
	 * 다운로드에서도 사라졌고, 다시 시도하면 404 를 받는다. "실패했으니 다시 해보라" 고
	 * 말해놓고 다시 하면 "그런 것 없다" 고 답하는 셈이다.
	 *
	 * <p>남는 고아 객체는 <b>사용자의 문제가 아니라 우리 쪽 정리 과제</b>다. 로그로 남기고
	 * 계약대로 204 를 준다. {@code PendingUploadSweeper.sweepOne} 이 같은 실패에
	 * 같은 판단을 한다.
	 */
	@Nested
	@DisplayName("객체 삭제가 실패해도 계약은 지킨다")
	class StorageFailure {

		@Test
		@DisplayName("204 를 준다 — 파일은 이미 사라졌으므로 실패가 아니다")
		void stillReportsSuccess() throws Exception {
			UUID fileId = upload("report.pdf");
			observer().failNextDelete();

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("사용자가 보는 상태는 완전히 삭제된 것과 같다")
		void looksFullyDeleted() throws Exception {
			UUID fileId = upload("report.pdf");
			observer().failNextDelete();

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());

			assertThat(listedNames()).isEmpty();
			mockMvc.perform(get("/api/files/{fileId}/content", fileId))
				.andExpect(status().isNotFound());
		}

		/**
		 * ★ 고아가 남았다는 사실 자체는 지워지지 않는다. {@code stored_key} 가 그대로라
		 * 버킷과 대조하면 무엇이 남았는지 찾아낼 수 있다 — 이것이 재시도 컬럼을 두지 않는
		 * 근거다({@code FileDeleteService} 주석 참고).
		 */
		@Test
		@DisplayName("객체는 남지만 기록으로 추적할 수 있다")
		void orphanStaysTraceable() throws Exception {
			UUID fileId = upload("report.pdf");
			String key = storedKeyOf(fileId);
			observer().failNextDelete();

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());

			assertThatCode(() -> storage.load(key).close())
				.as("삭제가 실패했으므로 객체는 그대로 있어야 한다 — 주입이 실제로 먹혔는지 확인")
				.doesNotThrowAnyException();

			Map<String, Object> row = jdbc.queryForMap(
				"SELECT stored_key, deleted_at FROM upload_audit WHERE file_id = ?::uuid",
				fileId.toString());
			assertThat(row.get("stored_key")).isEqualTo(key);
			assertThat(row.get("deleted_at")).isNotNull();
		}

		/** 두 번째 요청은 여전히 404 다 — 실패했다고 소유권이 돌아오지는 않는다. */
		@Test
		@DisplayName("삭제 실패 뒤에도 다시 지울 수는 없다")
		void ownershipIsNotReturned() throws Exception {
			UUID fileId = upload("report.pdf");
			observer().failNextDelete();

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());
			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNotFound());
		}
	}

	@Nested
	@DisplayName("지울 수 없는 것 — 전부 404 다")
	class NotFound {

		/** 두 번째 요청이 이미 지운 객체를 한 번 더 지우려 들면 안 된다. */
		@Test
		@DisplayName("두 번째 삭제는 404 이고 객체를 다시 건드리지 않는다")
		void secondDeleteIsNotFound() throws Exception {
			UUID fileId = upload("report.pdf");
			String key = storedKeyOf(fileId);

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNoContent());
			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));

			assertThat(observer().deletedKeys())
				.as("소유권을 얻지 못한 요청은 객체를 건드리지 않는다")
				.containsExactly(key);
		}

		/** 확정되지 않은 업로드는 스위퍼의 몫이다. 여기서 손대면 두 정리 주체가 겹친다. */
		@Test
		@DisplayName("PENDING 은 404 다 — 객체가 있어도 지우지 않는다")
		void pending() throws Exception {
			UUID fileId = storeWithResult("half.pdf", "PENDING", null);

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));

			assertThat(observer().deletedKeys()).isEmpty();
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM upload_audit WHERE file_id = ?::uuid AND result = 'PENDING'",
				Long.class, fileId.toString())).isEqualTo(1L);
		}

		@Test
		@DisplayName("ERROR 는 404 다")
		void error() throws Exception {
			UUID fileId = storeWithResult("broken.pdf", "ERROR", "STORAGE_UNAVAILABLE");

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));

			assertThat(observer().deletedKeys()).isEmpty();
		}

		@Test
		@DisplayName("모르는 식별자는 404 다")
		void unknown() throws Exception {
			mockMvc.perform(delete("/api/files/{fileId}", UUID.randomUUID())
					.header("X-Admin-Token", ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
		}
	}

	/**
	 * 업로드·조회·다운로드는 공개지만 <b>삭제는 파괴적</b>이다. 공개로 두면 누구나 남의
	 * 파일을 지울 수 있고, 그러면 목록도 다운로드도 의미가 없어진다.
	 */
	@Nested
	@DisplayName("★ 삭제는 관리 토큰을 요구한다")
	class RequiresAdminToken {

		@Test
		@DisplayName("토큰 없이 삭제하면 401 이고 파일은 그대로다")
		void missingToken() throws Exception {
			UUID fileId = upload("report.pdf");

			mockMvc.perform(delete("/api/files/{fileId}", fileId))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_TOKEN_REQUIRED"));

			assertThat(listedNames()).containsExactly("report.pdf");
			assertThat(observer().deletedKeys()).isEmpty();
		}

		@Test
		@DisplayName("틀린 토큰은 401 이다")
		void wrongToken() throws Exception {
			UUID fileId = upload("report.pdf");

			mockMvc.perform(delete("/api/files/{fileId}", fileId)
					.header("X-Admin-Token", "wrong-token-wrong-token-wrong-to"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_TOKEN_INVALID"));

			assertThat(listedNames()).containsExactly("report.pdf");
		}

		/** 과제가 요구하는 "누구나 접속 가능" 은 업로드·조회에 걸린다. 그쪽은 건드리지 않는다. */
		@Test
		@DisplayName("업로드와 목록은 토큰 없이 그대로 열려 있다")
		void uploadAndListStayPublic() throws Exception {
			upload("public.pdf");

			mockMvc.perform(get("/api/files")).andExpect(status().isOk());
			assertThat(listedNames()).containsExactly("public.pdf");
		}
	}

	/**
	 * 객체를 지우는 <b>그 순간</b>의 DB 상태를 기록하는 관측자.
	 *
	 * <p>모킹이 아니다 — 실제 {@link MinioObjectStorage} 에 그대로 위임하고, 지나가는 호출만
	 * 적는다. 삭제 순서는 정상 경로의 응답만 봐서는 드러나지 않기 때문에 이 관측이 필요하다.
	 *
	 * <p>별도 커넥션으로 읽으므로 <b>커밋된 상태만</b> 보인다. 소유권 UPDATE 가 커밋되기 전에
	 * 객체를 지우면 여기서 {@code false} 가 잡힌다.
	 */
	static final class DeletionOrderObserver implements ObjectStorage {

		private final ObjectStorage delegate;
		private final JdbcTemplate jdbc;
		private final List<String> deletedKeys = new CopyOnWriteArrayList<>();
		private final Map<String, Boolean> markedWhenDeleted = new ConcurrentHashMap<>();
		private volatile boolean failNextDelete;

		DeletionOrderObserver(ObjectStorage delegate, JdbcTemplate jdbc) {
			this.delegate = delegate;
			this.jdbc = jdbc;
		}

		void reset() {
			deletedKeys.clear();
			markedWhenDeleted.clear();
			failNextDelete = false;
		}

		/** 스토리지 장애를 흉내 낸다. 실제로 MinIO 를 죽일 수는 없으니 이 한 지점만 주입한다. */
		void failNextDelete() {
			this.failNextDelete = true;
		}

		List<String> deletedKeys() {
			return List.copyOf(deletedKeys);
		}

		boolean rowWasMarkedWhenDeleted(String key) {
			return Boolean.TRUE.equals(markedWhenDeleted.get(key));
		}

		@Override
		public void store(StorageKey key, InputStream content, long size) {
			delegate.store(key, content, size);
		}

		@Override
		public InputStream load(String key) {
			return delegate.load(key);
		}

		@Override
		public void delete(String key) {
			Long marked = jdbc.queryForObject(
				"SELECT count(*) FROM upload_audit WHERE stored_key = ? AND deleted_at IS NOT NULL",
				Long.class, key);
			markedWhenDeleted.put(key, marked != null && marked > 0);
			deletedKeys.add(key);
			if (failNextDelete) {
				failNextDelete = false;
				throw new StorageException("주입된 삭제 실패");
			}
			delegate.delete(key);
		}
	}

	@TestConfiguration
	static class ObservingStorage {

		/**
		 * 실제 스토리지를 감싼다. {@code StorageConfig} 의 빈은 그대로 두고 이쪽을
		 * {@code @Primary} 로 앞세운다 — 배선을 바꾸는 것이 아니라 한 겹 덧대는 것이다.
		 */
		@Bean
		@Primary
		ObjectStorage observingObjectStorage(MinioClient client, StorageProperties properties,
				JdbcTemplate jdbc) {
			return new DeletionOrderObserver(new MinioObjectStorage(client, properties.bucket()), jdbc);
		}
	}
}

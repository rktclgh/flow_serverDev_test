package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

import flow.test.serverdev.storage.ObjectStorage;
import flow.test.serverdev.storage.StorageKey;
import flow.test.serverdev.storage.StorageKeyGenerator;
import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 다운로드. (SPEC §7.6, §21.8)
 *
 * <p>이 엔드포인트의 목적은 편의가 아니라 <b>실증</b>이다. §9 의 저장 규칙(파일명을 키에
 * 쓰지 않는다, Content-Type 을 강제한다)이 문서에만 있으면 지켜지는지 알 수 없다.
 * 올린 것을 도로 받아보는 경로가 있어야 그것이 코드로 확인된다.
 *
 * <p><b>{@code ALLOWED} 가 아닌 행은 전부 404 다.</b> 상태별로 다르게 답하면 응답 차이만으로
 * "그 식별자는 존재하지만 차단됐다" 를 알아낼 수 있다 — 존재 여부조차 알리지 않는다.
 */
@AutoConfigureMockMvc
@DisplayName("파일 다운로드")
class FileDownloadControllerTest extends IntegrationTest {

	private static final byte[] BODY = "hello download".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectStorage storage;

	@Autowired
	private StorageKeyGenerator keyGenerator;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
		jdbc.update("DELETE FROM upload_audit");
	}

	/** 업로드 API 를 그대로 거쳐 올린다. 조립한 픽스처가 아니라 실제로 만들어진 행을 본다. */
	private UUID upload(String filename) throws Exception {
		MvcResult result = mockMvc.perform(multipart("/api/files")
				.file(new MockMultipartFile("file", filename,
					MediaType.APPLICATION_OCTET_STREAM_VALUE, BODY)))
			.andExpect(status().isCreated())
			.andReturn();

		JsonNode body = json.readTree(result.getResponse().getContentAsString());
		return UUID.fromString(body.get("fileId").asText());
	}

	/**
	 * 객체는 실제로 저장하고 행만 다른 상태로 심는다.
	 *
	 * <p>객체 없이 행만 만들면 "상태가 아니라 객체가 없어서" 404 가 나올 수 있다. 그러면
	 * 상태 조건을 지워도 테스트가 통과해 <b>아무것도 지키지 못한다.</b>
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

	private static String contentDisposition(MvcResult result) {
		return result.getResponse().getHeader("Content-Disposition");
	}

	/**
	 * {@code filename*=UTF-8''} 값을 되돌린다.
	 *
	 * <p>스프링의 {@code ContentDisposition} 은 RFC 6266 권고대로 <b>ASCII fallback
	 * {@code filename="..."} 을 함께</b> 낸다. SPEC §7.6 은 {@code filename*} 만 적었지만,
	 * 그것을 없애려면 헤더 문자열을 손으로 조립해야 한다 — 규격이 권하는 쪽을 남기고
	 * 이 테스트가 <b>둘 다 ASCII 인지</b>까지 본다.
	 */
	private static String decodeFilename(String disposition) {
		String marker = "filename*=UTF-8''";
		assertThat(disposition).startsWith("attachment;").contains(marker);
		String encoded = disposition.substring(disposition.indexOf(marker) + marker.length());
		return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
	}

	@Nested
	@DisplayName("허용된 파일")
	class Allowed {

		@Test
		@DisplayName("올린 바이트가 그대로 돌아온다")
		void roundTrip() throws Exception {
			UUID fileId = upload("report.pdf");

			byte[] downloaded = mockMvc.perform(get("/api/files/{fileId}/content", fileId))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray();

			assertThat(downloaded).isEqualTo(BODY);
		}

		/**
		 * 세 헤더가 함께 있어야 의미가 있다. {@code octet-stream} 만으로는 부족하고
		 * ({@code nosniff} 없이는 브라우저가 내용을 보고 스스로 판단한다),
		 * {@code attachment} 없이는 탭에서 그대로 열린다.
		 */
		@Test
		@DisplayName("octet-stream · attachment · nosniff 를 함께 보낸다")
		void headers() throws Exception {
			UUID fileId = upload("report.pdf");

			MvcResult result = mockMvc.perform(get("/api/files/{fileId}/content", fileId))
				.andExpect(status().isOk())
				.andReturn();

			assertThat(result.getResponse().getContentType())
				.isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
			assertThat(contentDisposition(result)).startsWith("attachment;");
			assertThat(result.getResponse().getHeader("X-Content-Type-Options"))
				.as("내용을 보고 브라우저가 타입을 정하면 저장 시점의 방어가 무의미해진다")
				.isEqualTo("nosniff");
		}

		/**
		 * 헤더는 ASCII 만 실을 수 있다. 한글을 그대로 넣으면 서버·프록시·브라우저가 각자
		 * 다른 인코딩으로 읽어 파일명이 깨진다. RFC 5987 이 그것을 위한 규격이다.
		 */
		@Test
		@DisplayName("한글·공백 파일명을 RFC 5987 로 실어 보낸다")
		void rfc5987() throws Exception {
			UUID fileId = upload("분기 보고서.pdf");

			MvcResult result = mockMvc.perform(get("/api/files/{fileId}/content", fileId))
				.andExpect(status().isOk())
				.andReturn();

			assertThat(contentDisposition(result))
				.as("헤더 값은 ASCII 여야 한다")
				.isEqualTo(contentDisposition(result).replaceAll("[^\\x00-\\x7F]", "?"));
			assertThat(decodeFilename(contentDisposition(result))).isEqualTo("분기 보고서.pdf");
		}

		/**
		 * ★ 기록에 남은 <b>이스케이프된 값을 그대로</b> 쓴다(SPEC §21.8). 원본을 따로 보관하지
		 * 않는 이유는, 이스케이프 대상이 {@code Cc}/{@code Cf} 뿐이라 한글·이모지·공백은
		 * 살아남고 걸리는 것은 RTL 재정의 같은 것뿐이기 때문이다. 그런 문자가
		 * {@code Content-Disposition} 으로 나가면 그것이 곧 파일명 스푸핑이다.
		 */
		@Test
		@DisplayName("이스케이프된 파일명을 되돌리지 않는다")
		void keepsEscapedName() throws Exception {
			UUID fileId = storeWithResult("safe.jpg\\u202e.exe", "ALLOWED", null);

			MvcResult result = mockMvc.perform(get("/api/files/{fileId}/content", fileId))
				.andExpect(status().isOk())
				.andReturn();

			assertThat(decodeFilename(contentDisposition(result))).isEqualTo("safe.jpg\\u202e.exe");
		}
	}

	@Nested
	@DisplayName("★ ALLOWED 가 아니면 존재 여부조차 알리지 않는다")
	class NotAllowed {

		@Test
		@DisplayName("PENDING 은 404 다 — 객체가 있어도 내보내지 않는다")
		void pending() throws Exception {
			UUID fileId = storeWithResult("pending.pdf", "PENDING", null);

			mockMvc.perform(get("/api/files/{fileId}/content", fileId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
		}

		/** 확정되지 못한 업로드는 스위퍼가 지울 대상이다. 그 사이에 내보내면 안 된다. */
		@Test
		@DisplayName("ERROR 는 404 다 — 객체가 있어도 내보내지 않는다")
		void error() throws Exception {
			UUID fileId = storeWithResult("error.pdf", "ERROR", "STORAGE_UNAVAILABLE");

			mockMvc.perform(get("/api/files/{fileId}/content", fileId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
		}

		/**
		 * {@code BLOCKED} 행은 {@code file_id} 가 NULL 이라(DB 제약) <b>지목 자체가 불가능하다.</b>
		 * 그래서 차단된 파일을 요청하는 경로는 "모르는 식별자" 와 같은 경로로 수렴한다.
		 */
		@Test
		@DisplayName("모르는 식별자는 404 다")
		void unknown() throws Exception {
			UUID blockedFileId = jdbc.queryForObject("""
				INSERT INTO upload_audit (original_filename, result, reason_code)
				VALUES ('setup.exe', 'BLOCKED', 'FILE_BLOCKED_EXTENSION')
				RETURNING coalesce(file_id, '%s'::uuid)
				""".formatted(UUID.randomUUID()), UUID.class);

			mockMvc.perform(get("/api/files/{fileId}/content", blockedFileId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
		}
	}
}

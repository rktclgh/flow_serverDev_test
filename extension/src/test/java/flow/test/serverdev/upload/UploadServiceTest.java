package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.audit.UploadAuditRecorder;
import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.storage.StorageException;
import flow.test.serverdev.storage.StorageKeyGenerator;
import flow.test.serverdev.storage.StorageOutcomeUnknownException;
import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 업로드 처리. (SPEC §21.6)
 *
 * <p>여기서 지켜야 하는 것은 "잘 저장된다" 가 아니라 <b>실패했을 때 무엇을 확정하지 않는가</b> 다.
 * 저장 결과를 모르는 채로 {@code ERROR} 를 찍으면 실제로 저장된 객체가 정리 대상에서 사라져
 * 아무도 찾지 못하는 고아가 된다.
 */
@DisplayName("업로드 처리")
class UploadServiceTest extends IntegrationTest {

	private static final byte[] BODY = "hello".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private UploadService service;

	@Autowired
	private UploadValidator validator;

	@Autowired
	private StorageKeyGenerator keyGenerator;

	@Autowired
	private UploadAuditRecorder audit;

	@Autowired
	private JdbcTemplate jdbc;

	private FakeObjectStorage fake;
	private UploadService withFake;

	@BeforeEach
	void setUp() {
		PolicyFixture.reset(jdbc);
		jdbc.update("DELETE FROM upload_audit");
		fake = new FakeObjectStorage();
		withFake = new UploadService(validator, keyGenerator, fake, audit);
	}

	private static InputStream body() {
		return new ByteArrayInputStream(BODY);
	}

	private static InetAddress client() throws Exception {
		return InetAddress.getByName("203.0.113.7");
	}

	private Map<String, Object> onlyAuditRow() {
		return jdbc.queryForMap("SELECT result, reason_code, stored_key, matched_extension FROM upload_audit");
	}

	private long auditCount() {
		return jdbc.queryForObject("SELECT count(*) FROM upload_audit", Long.class);
	}

	@Nested
	@DisplayName("허용")
	class Allowed {

		@Test
		@DisplayName("객체가 저장되고 감사가 ALLOWED 로 확정된다")
		void storesAndFinalises() throws Exception {
			UploadOutcome outcome = service.upload("report.pdf", BODY.length, body(), client());

			assertThat(outcome).isInstanceOf(UploadOutcome.Stored.class);
			Map<String, Object> row = onlyAuditRow();
			assertThat(row).containsEntry("result", "ALLOWED").containsEntry("matched_extension", "pdf");
			assertThat((String) row.get("stored_key"))
				.endsWith("/" + ((UploadOutcome.Stored) outcome).fileId());
		}
	}

	@Nested
	@DisplayName("거부")
	class Rejected {

		@Test
		@DisplayName("차단 확장자는 BLOCKED 로 기록되고 스토리지에 손대지 않는다")
		void blockedIsRecordedWithoutStorage() throws Exception {
			jdbc.update("UPDATE blocked_extension SET is_blocked = TRUE WHERE name = ?", "exe");

			UploadOutcome outcome = withFake.upload("setup.exe", BODY.length, body(), client());

			assertThat(((UploadOutcome.Rejected) outcome).code())
				.isEqualTo(ErrorCode.FILE_BLOCKED_EXTENSION);
			assertThat(onlyAuditRow())
				.containsEntry("result", "BLOCKED")
				.containsEntry("reason_code", "FILE_BLOCKED_EXTENSION")
				.containsEntry("stored_key", null);
			assertThat(fake.isEmpty()).as("거부는 스토리지에 도달하지 않는다").isTrue();
		}
	}

	@Nested
	@DisplayName("저장 실패")
	class Failures {

		@Test
		@DisplayName("확정 실패는 ERROR 로 확정한다 — 객체가 없는 것이 확실하다")
		void definiteFailureIsFinalised() throws Exception {
			fake.failWith(FakeObjectStorage.definiteFailure());

			assertThatThrownBy(() -> withFake.upload("report.pdf", BODY.length, body(), client()))
				.isInstanceOf(StorageException.class);

			assertThat(onlyAuditRow())
				.containsEntry("result", "ERROR")
				.containsEntry("reason_code", "STORAGE_UNAVAILABLE");
		}

		/**
		 * ★ 이 패킷에서 가장 중요한 분기다. 저장됐는지 모르는데 {@code ERROR} 로 확정하면
		 * 실제로 저장된 객체가 {@code PENDING} 정리 대상에서 사라져 영원히 남는다.
		 */
		@Test
		@DisplayName("★ 결과가 불명이면 아무것도 확정하지 않는다 — PENDING 으로 남긴다")
		void unknownOutcomeStaysPending() throws Exception {
			fake.failWith(FakeObjectStorage.unknownOutcome());

			assertThatThrownBy(() -> withFake.upload("report.pdf", BODY.length, body(), client()))
				.isInstanceOf(StorageOutcomeUnknownException.class);

			assertThat(onlyAuditRow())
				.as("확정하면 스위퍼가 찾지 못한다")
				.containsEntry("result", "PENDING")
				.containsEntry("reason_code", null);
		}

		@Test
		@DisplayName("자리를 먼저 잡으므로 실패해도 기록은 정확히 한 줄이다")
		void exactlyOneRow() throws Exception {
			fake.failWith(FakeObjectStorage.unknownOutcome());

			assertThatThrownBy(() -> withFake.upload("report.pdf", BODY.length, body(), client()))
				.isInstanceOf(StorageOutcomeUnknownException.class);

			assertThat(auditCount()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("확정의 멱등성")
	class Idempotency {

		/**
		 * 커밋이 실패했는지 응답만 못 받았는지 호출자는 구분하지 못한다. 두 번째 확정에서 예외가
		 * 나면 <b>실제로는 성공한 업로드를 실패로 보고</b>하게 된다.
		 */
		@Test
		@DisplayName("markAllowed 를 두 번 불러도 성공한다")
		void markAllowedTwice() throws Exception {
			UploadOutcome outcome = service.upload("report.pdf", BODY.length, body(), client());
			long id = jdbc.queryForObject("SELECT id FROM upload_audit", Long.class);

			audit.markAllowed(id);

			assertThat(outcome).isInstanceOf(UploadOutcome.Stored.class);
			assertThat(onlyAuditRow()).containsEntry("result", "ALLOWED");
		}
	}
}

package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import flow.test.serverdev.support.IntegrationTest;

/**
 * 감사 기록기. (SPEC §3.2, §8.2)
 *
 * <p>여기서 확인하는 것은 두 가지다 — <b>두 단계 프로토콜이 실제로 동작하는가</b>와
 * <b>기록이 호출자의 롤백에서 살아남는가</b>. 후자가 이 클래스의 존재 이유에 가깝다.
 */
@DisplayName("업로드 감사 기록기")
class UploadAuditRecorderTest extends IntegrationTest {

	private static final String NUL = Character.toString(0x0000);

	@Autowired
	UploadAuditRecorder recorder;

	@Autowired
	UploadAuditRepository repository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clear() {
		jdbc.update("DELETE FROM upload_audit");
	}

	@Nested
	@DisplayName("★ 기록은 호출자의 실패에서 살아남는다")
	class SurvivesCallerRollback {

		/**
		 * 차단 기록이 필요한 상황은 <b>요청을 거부하는 상황</b>이다. 기록이 호출자와 같은
		 * 트랜잭션에 묶여 있으면, 거부하며 롤백하는 순간 거부했다는 사실도 함께 사라진다.
		 * 그러면 이 기능은 "성공한 업로드만 기록하는" 쓸모없는 것이 된다.
		 */
		@Test
		@DisplayName("호출자 트랜잭션이 롤백돼도 차단 기록은 남는다")
		void blockedRecordSurvives() {
			transactionTemplate.execute(status -> {
				recorder.recordBlocked(attempt("a.exe"), "FILE_BLOCKED_EXTENSION");
				status.setRollbackOnly();
				return null;
			});

			assertThat(repository.findAll())
				.singleElement()
				.satisfies(audit -> {
					assertThat(audit.result()).isEqualTo(UploadResult.BLOCKED);
					assertThat(audit.reasonCode()).isEqualTo("FILE_BLOCKED_EXTENSION");
				});
		}

		@Test
		@DisplayName("호출자 트랜잭션이 롤백돼도 PENDING 자리는 남는다")
		void pendingSurvives() {
			transactionTemplate.execute(status -> {
				recorder.beginPending(attempt("a.pdf"), "2026/08/29/key");
				status.setRollbackOnly();
				return null;
			});

			assertThat(repository.findAll()).singleElement()
				.satisfies(audit -> assertThat(audit.result()).isEqualTo(UploadResult.PENDING));
		}
	}

	@Nested
	@DisplayName("두 단계 기록")
	class TwoPhase {

		@Test
		@DisplayName("자리를 잡고 성공으로 확정한다")
		void beginThenAllow() {
			long id = recorder.beginPending(attempt("a.pdf"), "2026/08/29/k1");

			assertThat(repository.findById(id).orElseThrow().result())
				.isEqualTo(UploadResult.PENDING);

			recorder.markAllowed(id);

			assertThat(repository.findById(id).orElseThrow().result())
				.isEqualTo(UploadResult.ALLOWED);
		}

		@Test
		@DisplayName("자리를 잡고 실패로 확정한다")
		void beginThenError() {
			long id = recorder.beginPending(attempt("a.pdf"), "2026/08/29/k2");

			recorder.markError(id, "STORAGE_UNAVAILABLE");

			UploadAudit audit = repository.findById(id).orElseThrow();
			assertThat(audit.result()).isEqualTo(UploadResult.ERROR);
			assertThat(audit.reasonCode()).isEqualTo("STORAGE_UNAVAILABLE");
			assertThat(audit.storedKey()).isEqualTo("2026/08/29/k2");
		}

		@Test
		@DisplayName("같은 상태로의 재확정은 조용히 통과한다 — 재시도가 실패로 보고되면 안 된다")
		void confirmingTwiceIsIdempotent() {
			long id = recorder.beginPending(attempt("a.pdf"), "2026/08/29/k3");
			recorder.markAllowed(id);

			recorder.markAllowed(id);

			assertThat(jdbc.queryForObject(
				"SELECT result FROM upload_audit WHERE id = ?", String.class, id))
				.isEqualTo("ALLOWED");
		}

		@Test
		@DisplayName("확정된 기록을 다른 상태로 바꿀 수는 없다")
		void cannotChangeToAnotherState() {
			long id = recorder.beginPending(attempt("a.pdf"), "2026/08/29/k3b");
			recorder.markAllowed(id);

			assertThatThrownBy(() -> recorder.markError(id, "STORAGE_UNAVAILABLE"))
				.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("없는 기록을 확정하려 하면 실패한다")
		void missingRecord() {
			assertThatThrownBy(() -> recorder.markAllowed(999_999L))
				.isInstanceOf(IllegalStateException.class);
		}

		/**
		 * 두 단계 프로토콜이 주장하는 성질 — 잔여물은 조용히 새지 않고
		 * {@code PENDING} 으로 남아 조회로 찾을 수 있다.
		 */
		@Test
		@DisplayName("확정되지 못한 기록은 조회로 찾을 수 있다")
		void stalePendingIsDiscoverable() {
			recorder.beginPending(attempt("stale.pdf"), "2026/08/29/k4");
			long confirmed = recorder.beginPending(attempt("ok.pdf"), "2026/08/29/k5");
			recorder.markAllowed(confirmed);

			assertThat(repository.findStalePending(OffsetDateTime.now().plusMinutes(1)))
				.singleElement()
				.satisfies(audit -> assertThat(audit.storedKey()).isEqualTo("2026/08/29/k4"));
		}
	}

	@Nested
	@DisplayName("파일명은 기록기가 이스케이프한다")
	class Sanitisation {

		/** 호출부에 맡기면 언젠가 한 곳이 빠지고, 그 한 곳이 하필 공격 경로가 된다. */
		@Test
		@DisplayName("호출자가 원본을 그대로 넘겨도 제어문자가 남지 않는다")
		void escapesWithoutCallerHelp() {
			recorder.recordBlocked(attempt("safe.jpg" + NUL + ".exe"), "FILE_NAME_INVALID");

			assertThat(repository.findAll()).singleElement()
				.satisfies(audit -> assertThat(audit.originalFilename())
					.isEqualTo("safe.jpg\\u0000.exe"));
		}

		@Test
		@DisplayName("클라이언트 주소는 그대로 기록된다")
		void addressIsPreserved() throws UnknownHostException {
			InetAddress address = InetAddress.getByName("2001:db8::1");

			recorder.recordBlocked(
				new UploadAttempt("a.exe", address, 10L, "exe", null), "FILE_BLOCKED_EXTENSION");

			assertThat(repository.findAll()).singleElement()
				.satisfies(audit -> assertThat(audit.clientIp()).isEqualTo(address));
		}
	}

	private static UploadAttempt attempt(String filename) {
		return new UploadAttempt(filename, null, 1024L, null, null);
	}
}

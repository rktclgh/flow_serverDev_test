package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.storage.StorageKey;
import flow.test.serverdev.support.IntegrationTest;

/**
 * 감사 엔티티가 실제 스키마와 맞물리는지 검증한다.
 *
 * <p><b>이 테스트가 도는 것 자체가 하나의 검증이다.</b> 스프링 컨텍스트가 뜨려면
 * {@code ddl-auto=validate} 가 통과해야 하고, 그것은 {@code InetAddress} 필드가
 * {@code INET} 컬럼과 짝이 맞는다는 뜻이다. Hibernate 문서가 그렇게 매핑한다고
 * 적고 있지만, 확인하지 않고 믿을 이유는 없다.
 */
@DisplayName("업로드 감사 매핑")
class UploadAuditMappingTest extends IntegrationTest {

	@Autowired
	UploadAuditRepository repository;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clear() {
		jdbc.update("DELETE FROM upload_audit");
	}

	@Nested
	@DisplayName("client_ip — INET 컬럼과의 왕복")
	class ClientIp {

		@Test
		@DisplayName("IPv4 가 왕복한다")
		void ipv4RoundTrip() throws UnknownHostException {
			InetAddress address = InetAddress.getByName("192.0.2.1");

			UploadAudit saved = repository.save(
				UploadAudit.blocked(attempt("a.exe", address), "FILE_BLOCKED_EXTENSION"));

			assertThat(repository.findById(saved.id()).orElseThrow().clientIp())
				.isEqualTo(address);
		}

		@Test
		@DisplayName("IPv6 가 왕복한다")
		void ipv6RoundTrip() throws UnknownHostException {
			InetAddress address = InetAddress.getByName("2001:db8::1");

			UploadAudit saved = repository.save(
				UploadAudit.blocked(attempt("a.exe", address), "FILE_BLOCKED_EXTENSION"));

			assertThat(repository.findById(saved.id()).orElseThrow().clientIp())
				.isEqualTo(address);
		}

		/** 저장된 컬럼이 정말 INET 인지 — 문자열로 눕지 않았는지 DB 쪽에서 확인한다. */
		@Test
		@DisplayName("DB 에 INET 으로 저장되어 family() 로 4/6 을 구분할 수 있다")
		void storedAsInet() throws UnknownHostException {
			repository.save(UploadAudit.blocked(
				attempt("v4.exe", InetAddress.getByName("192.0.2.1")), "FILE_BLOCKED_EXTENSION"));
			repository.save(UploadAudit.blocked(
				attempt("v6.exe", InetAddress.getByName("2001:db8::1")), "FILE_BLOCKED_EXTENSION"));

			assertThat(jdbc.queryForList(
				"SELECT family(client_ip) FROM upload_audit ORDER BY 1", Integer.class))
				.containsExactly(4, 6);
		}

		@Test
		@DisplayName("주소를 얻지 못한 경우 NULL 로 남는다")
		void nullAddress() {
			UploadAudit saved = repository.save(
				UploadAudit.blocked(attempt("a.exe", null), "FILE_BLOCKED_EXTENSION"));

			assertThat(repository.findById(saved.id()).orElseThrow().clientIp()).isNull();
		}
	}

	@Nested
	@DisplayName("두 단계 기록 프로토콜")
	class TwoPhase {

		@Test
		@DisplayName("PENDING 은 확정 상태로 갈 수 있다")
		void pendingBecomesAllowed() {
			UploadAudit saved = repository.saveAndFlush(
				UploadAudit.pending(attempt("a.pdf", null), key("2026/08/29/k1")));

			saved.markAllowed();
			repository.saveAndFlush(saved);

			assertThat(repository.findById(saved.id()).orElseThrow().result())
				.isEqualTo(UploadResult.ALLOWED);
		}

		@Test
		@DisplayName("PENDING 은 실패 사유와 함께 ERROR 가 될 수 있다")
		void pendingBecomesError() {
			UploadAudit saved = repository.saveAndFlush(
				UploadAudit.pending(attempt("a.pdf", null), key("2026/08/29/k2")));

			saved.markError("STORAGE_UNAVAILABLE");
			repository.saveAndFlush(saved);

			UploadAudit reloaded = repository.findById(saved.id()).orElseThrow();
			assertThat(reloaded.result()).isEqualTo(UploadResult.ERROR);
			assertThat(reloaded.reasonCode()).isEqualTo("STORAGE_UNAVAILABLE");
		}

		/** 확정된 기록은 도메인에서 먼저 막는다. DB 트리거는 그 뒤의 방어선이다. */
		@Test
		@DisplayName("확정된 기록은 다른 상태로 전이시킬 수 없다")
		void finalStateIsFinal() {
			UploadAudit saved = repository.saveAndFlush(
				UploadAudit.pending(attempt("a.pdf", null), key("2026/08/29/k3")));
			saved.markAllowed();

			assertThatThrownBy(() -> saved.markError("STORAGE_UNAVAILABLE"))
				.isInstanceOf(IllegalStateException.class);
		}

		/**
		 * ★ 같은 상태로의 재확정만 예외다. 커밋이 실패했는지 응답만 못 받았는지 호출자는
		 * 구분하지 못하므로(SPEC §21.6), 두 번째 {@code markAllowed} 에서 예외가 나면
		 * <b>실제로 성공한 업로드를 실패로 보고</b>하게 된다. 되돌리기가 아니라 재시도다.
		 */
		@Test
		@DisplayName("ALLOWED 를 다시 ALLOWED 로 두는 것은 허용한다 — 되돌리기가 아니라 재시도다")
		void reconfirmingAllowedIsNoOp() {
			UploadAudit saved = repository.saveAndFlush(
				UploadAudit.pending(attempt("a.pdf", null), key("2026/08/29/k4")));
			saved.markAllowed();

			saved.markAllowed();

			assertThat(saved.result()).isEqualTo(UploadResult.ALLOWED);
		}

		@Test
		@DisplayName("ERROR 를 ALLOWED 로 되돌릴 수는 없다")
		void errorCannotBecomeAllowed() {
			UploadAudit saved = repository.saveAndFlush(
				UploadAudit.pending(attempt("a.pdf", null), key("2026/08/29/k5")));
			saved.markError("STORAGE_UNAVAILABLE");

			assertThatThrownBy(saved::markAllowed).isInstanceOf(IllegalStateException.class);
		}
	}

	private static StorageKey key(String value) {
		return new StorageKey(UUID.randomUUID(), value);
	}

	private static UploadAttempt attempt(String filename, InetAddress clientIp) {
		return new UploadAttempt(filename, clientIp, 1024L, null, null);
	}
}

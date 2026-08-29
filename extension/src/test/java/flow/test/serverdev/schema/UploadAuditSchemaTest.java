package flow.test.serverdev.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import flow.test.serverdev.support.TestImages;

/**
 * {@code upload_audit} 의 제약이 실제로 강제되는지 검증한다. (SPEC §3.2)
 *
 * <p>감사 테이블에 도메인 규칙을 새기는 이유는 {@code blocked_extension} 과 같다 —
 * 앱의 검사는 친절한 오류를 주기 위한 것이고, <b>기록의 정합성은 DB 가 보증</b>해야 한다.
 * 감사 기록은 나중에 "그때 무슨 일이 있었나" 를 답하는 유일한 근거이므로,
 * 앞뒤가 맞지 않는 행이 하나라도 들어가면 전체의 신뢰가 떨어진다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. 검증 대상이 애플리케이션이 아니라 DDL 이다.
 */
@Testcontainers
@DisplayName("upload_audit 스키마")
class UploadAuditSchemaTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestImages.POSTGRES);

	static Connection connection;

	@BeforeAll
	static void migrate() throws SQLException {
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		connection = DriverManager.getConnection(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	@AfterAll
	static void closeConnection() throws SQLException {
		if (connection != null) {
			connection.close();
		}
	}

	@BeforeEach
	void clear() throws SQLException {
		execute("DELETE FROM upload_audit");
	}

	@Nested
	@DisplayName("result — 두 단계 기록 프로토콜")
	class Result {

		@Test
		@DisplayName("네 가지 상태를 저장한다")
		void allowedStates() {
			assertThatCode(() -> {
				insertBlocked("a.exe", "FILE_BLOCKED_EXTENSION");
				insertPending("b.pdf", "2026/08/29/key-b");
				insertAllowed("c.pdf", "2026/08/29/key-c");
				insertError("d.pdf", "STORAGE_UNAVAILABLE", "2026/08/29/key-d");
			}).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("정의되지 않은 상태는 거부한다")
		void unknownState() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result) VALUES ('a.pdf', 'MAYBE')
				"""))
				.hasMessageContaining("ck_upload_audit_result");
		}
	}

	@Nested
	@DisplayName("client_ip — IPv6 를 포함한 실제 주소만 들어간다")
	class ClientIp {

		@Test
		@DisplayName("IPv4 · IPv6 · IPv4-mapped 를 모두 저장한다")
		void bothFamilies() throws SQLException {
			insertWithIp("v4.pdf", "192.0.2.1");
			insertWithIp("v6.pdf", "2001:db8::1");
			insertWithIp("mapped.pdf", "::ffff:192.0.2.1");

			assertThat(queryOne("SELECT count(*) FROM upload_audit WHERE client_ip IS NOT NULL"))
				.isEqualTo("3");
		}

		/**
		 * <b>INET 을 택한 결정적 이유.</b> IPv6 는 같은 주소를 여러 방식으로 쓸 수 있어서,
		 * 문자열로 저장하면 한 주소가 여러 값으로 갈라진다. 그러면 감사 테이블에서
		 * IP 별 시도 횟수를 세는 것 자체가 불가능해진다.
		 */
		@Test
		@DisplayName("IPv6 표기 변형을 정규화해 한 값으로 모은다")
		void ipv6IsNormalised() throws SQLException {
			insertWithIp("a.pdf", "2001:0db8:0000:0000:0000:0000:0000:0001");
			insertWithIp("b.pdf", "2001:db8::1");

			assertThat(queryOne("SELECT count(DISTINCT client_ip) FROM upload_audit"))
				.isEqualTo("1");
		}

		@Test
		@DisplayName("형식이 깨진 주소는 거부한다")
		void malformed() {
			assertThatThrownBy(() -> insertWithIp("a.pdf", "999.1.1.1"))
				.hasMessageContaining("invalid input syntax for type inet");
		}

		@Test
		@DisplayName("주소를 얻지 못한 경우를 위해 NULL 은 허용한다")
		void nullAllowed() {
			assertThatCode(() -> insertBlocked("a.exe", "FILE_BLOCKED_EXTENSION"))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("stored_key — 상태와 앞뒤가 맞아야 한다")
	class StoredKey {

		@Test
		@DisplayName("ALLOWED 는 저장 키가 반드시 있다")
		void allowedRequiresKey() {
			// file_id 는 채운다. 비워두면 file_id 규칙까지 함께 어겨서
			// 이 테스트가 무엇을 확인한 것인지 흐려진다.
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, file_id)
				VALUES ('a.pdf', 'ALLOWED', '%s')
				""".formatted(UUID.randomUUID())))
				.hasMessageContaining("ck_upload_audit_stored_key");
		}

		@Test
		@DisplayName("PENDING 도 저장 키가 반드시 있다 — 키를 먼저 정하고 저장하기 때문")
		void pendingRequiresKey() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, file_id)
				VALUES ('a.pdf', 'PENDING', '%s')
				""".formatted(UUID.randomUUID())))
				.hasMessageContaining("ck_upload_audit_stored_key");
		}

		@Test
		@DisplayName("BLOCKED 는 저장 키를 가질 수 없다 — 저장하지 않았으므로")
		void blockedForbidsKey() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, reason_code, stored_key)
				VALUES ('a.exe', 'BLOCKED', 'FILE_BLOCKED_EXTENSION', '2026/08/29/key')
				"""))
				.hasMessageContaining("ck_upload_audit_stored_key");
		}

		/** 한 객체를 두 기록이 가리키면 어느 쪽이 사실인지 알 수 없다. */
		@Test
		@DisplayName("같은 저장 키를 두 기록이 가질 수 없다")
		void keyIsUnique() throws SQLException {
			insertAllowed("a.pdf", "2026/08/29/same");

			assertThatThrownBy(() -> insertAllowed("b.pdf", "2026/08/29/same"))
				.hasMessageContaining("uq_upload_audit_stored_key");
		}
	}

	@Nested
	@DisplayName("file_id — 클라이언트가 지목하는 식별자")
	class FileId {

		@Test
		@DisplayName("ALLOWED 는 식별자가 반드시 있다 — 없으면 아무도 그 파일을 지목할 수 없다")
		void allowedRequiresFileId() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, stored_key)
				VALUES ('a.pdf', 'ALLOWED', '2026/08/29/f1')
				"""))
				.hasMessageContaining("ck_upload_audit_result_file_id");
		}

		@Test
		@DisplayName("PENDING 도 식별자가 반드시 있다 — 키와 함께 정해지기 때문")
		void pendingRequiresFileId() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, stored_key)
				VALUES ('a.pdf', 'PENDING', '2026/08/29/f2')
				"""))
				.hasMessageContaining("ck_upload_audit_result_file_id");
		}

		@Test
		@DisplayName("BLOCKED 는 식별자를 가질 수 없다 — 저장하지 않았으므로 내보낼 것이 없다")
		void blockedForbidsFileId() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, reason_code, file_id)
				VALUES ('a.exe', 'BLOCKED', 'FILE_BLOCKED_EXTENSION', '%s')
				""".formatted(UUID.randomUUID())))
				.hasMessageContaining("ck_upload_audit_result_file_id");
		}

		/**
		 * 키가 있다는 것은 객체가 저장됐을 수 있다는 뜻이다. 그런데 식별자가 없으면
		 * <b>사람이 그 파일을 지목할 방법이 없다</b> — 스위퍼만 키로 접근할 수 있고
		 * 조회로는 영영 찾지 못한다.
		 */
		@Test
		@DisplayName("키가 있으면 ERROR 라도 식별자가 있어야 한다")
		void storedKeyImpliesFileId() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, reason_code, stored_key)
				VALUES ('a.pdf', 'ERROR', 'STORAGE_UNAVAILABLE', '2026/08/29/f3')
				"""))
				.hasMessageContaining("ck_upload_audit_stored_key_file_id");
		}

		@Test
		@DisplayName("키가 없는 ERROR 는 식별자도 없다 — 저장을 시도조차 못 한 경우다")
		void keylessErrorNeedsNothing() {
			assertThatCode(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, reason_code)
				VALUES ('a.pdf', 'ERROR', 'STORAGE_UNAVAILABLE')
				"""))
				.doesNotThrowAnyException();
		}

		/** 두 기록이 같은 식별자를 가지면 다운로드가 어느 파일을 내보낼지 알 수 없다. */
		@Test
		@DisplayName("같은 식별자를 두 기록이 가질 수 없다")
		void fileIdIsUnique() throws SQLException {
			UUID shared = UUID.randomUUID();
			insertAllowed("a.pdf", "2026/08/29/f4", shared);

			assertThatThrownBy(() -> insertAllowed("b.pdf", "2026/08/29/f5", shared))
				.hasMessageContaining("uq_upload_audit_file_id");
		}

		/**
		 * ★ 트리거의 불변 컬럼 목록에서 빠지면 <b>새 컬럼만 보증 밖으로 샌다.</b>
		 * 나머지를 아무리 잠가도 "이 기록이 그 파일이다" 를 나중에 고쳐 쓸 수 있으면
		 * 기록 전체를 믿을 수 없다. {@code reason_code} 가 정확히 그렇게 빠져 있었다.
		 */
		@Test
		@DisplayName("기록된 식별자는 바꿀 수 없다")
		void fileIdIsImmutable() throws SQLException {
			insertPending("a.pdf", "2026/08/29/f6");

			assertThatThrownBy(() -> execute(
				"UPDATE upload_audit SET file_id = '%s'".formatted(UUID.randomUUID())))
				.hasMessageContaining("cannot change");
		}
	}

	@Nested
	@DisplayName("reason_code — 실패는 이유 없이 기록될 수 없다")
	class ReasonCode {

		@Test
		@DisplayName("BLOCKED 는 사유가 반드시 있다")
		void blockedRequiresReason() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result) VALUES ('a.exe', 'BLOCKED')
				"""))
				.hasMessageContaining("ck_upload_audit_reason");
		}

		@Test
		@DisplayName("ERROR 도 사유가 반드시 있다")
		void errorRequiresReason() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result) VALUES ('a.pdf', 'ERROR')
				"""))
				.hasMessageContaining("ck_upload_audit_reason");
		}
	}

	@Nested
	@DisplayName("기록은 고쳐 쓸 수 없다")
	class Immutability {

		@Test
		@DisplayName("PENDING 에서 ALLOWED 로 갈 수 있다")
		void pendingToAllowed() throws SQLException {
			insertPending("a.pdf", "2026/08/29/k1");

			assertThatCode(() -> execute("UPDATE upload_audit SET result = 'ALLOWED'"))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("PENDING 에서 ERROR 로 갈 수 있다")
		void pendingToError() throws SQLException {
			insertPending("a.pdf", "2026/08/29/k2");

			assertThatCode(() -> execute(
				"UPDATE upload_audit SET result = 'ERROR', reason_code = 'STORAGE_UNAVAILABLE'"))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("확정된 기록의 상태는 되돌릴 수 없다")
		void finalStateIsFinal() throws SQLException {
			insertAllowed("a.pdf", "2026/08/29/k3");

			assertThatThrownBy(() -> execute("UPDATE upload_audit SET result = 'PENDING'"))
				.hasMessageContaining("cannot change");
		}

		/**
		 * ★ 사건을 설명하는 바로 그 필드다. 여기가 열려 있으면 나머지를 아무리 잠가도
		 * 기록을 믿을 수 없다. 외부 리뷰가 지적해 추가했다 —
		 * 트리거가 {@code reason_code} 만 비교 대상에서 빠뜨리고 있었다.
		 */
		@Test
		@DisplayName("확정된 기록의 사유는 고쳐 쓸 수 없다 — BLOCKED")
		void blockedReasonIsImmutable() throws SQLException {
			insertBlocked("a.exe", "FILE_BLOCKED_EXTENSION");

			assertThatThrownBy(() -> execute(
				"UPDATE upload_audit SET reason_code = 'FILE_NAME_INVALID'"))
				.hasMessageContaining("cannot change reason_code");
		}

		@Test
		@DisplayName("확정된 기록의 사유는 고쳐 쓸 수 없다 — ERROR")
		void errorReasonIsImmutable() throws SQLException {
			insertError("a.pdf", "STORAGE_UNAVAILABLE", "2026/08/29/k9");

			assertThatThrownBy(() -> execute(
				"UPDATE upload_audit SET reason_code = 'SOMETHING_ELSE'"))
				.hasMessageContaining("cannot change reason_code");
		}

		/** ALLOWED 는 사유가 없는 것이 정상이다. 나중에 붙일 수도 없어야 한다. */
		@Test
		@DisplayName("성공한 기록에 사유를 나중에 붙일 수 없다")
		void allowedCannotGainReason() throws SQLException {
			insertAllowed("a.pdf", "2026/08/29/k10");

			assertThatThrownBy(() -> execute(
				"UPDATE upload_audit SET reason_code = 'INVENTED'"))
				.hasMessageContaining("cannot change reason_code");
		}

		@Test
		@DisplayName("기록된 사실은 바꿀 수 없다 — 파일명")
		void filenameIsImmutable() throws SQLException {
			insertBlocked("a.exe", "FILE_BLOCKED_EXTENSION");

			assertThatThrownBy(() -> execute("UPDATE upload_audit SET original_filename = 'b.pdf'"))
				.hasMessageContaining("cannot change");
		}

		@Test
		@DisplayName("기록된 사실은 바꿀 수 없다 — client_ip")
		void ipIsImmutable() throws SQLException {
			insertWithIp("a.pdf", "192.0.2.1");

			assertThatThrownBy(() -> execute("UPDATE upload_audit SET client_ip = '192.0.2.9'"))
				.hasMessageContaining("cannot change");
		}
	}

	@Nested
	@DisplayName("값의 형식")
	class Format {

		@Test
		@DisplayName("matched_extension 은 정규화된 형태만 들어간다")
		void extensionFormat() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, reason_code, matched_extension)
				VALUES ('a.EXE', 'BLOCKED', 'FILE_BLOCKED_EXTENSION', '.EXE')
				"""))
				.hasMessageContaining("ck_upload_audit_extension_format");
		}

		@Test
		@DisplayName("음수 크기는 거부한다")
		void negativeSize() {
			assertThatThrownBy(() -> execute("""
				INSERT INTO upload_audit (original_filename, result, reason_code, size_bytes)
				VALUES ('a.exe', 'BLOCKED', 'FILE_BLOCKED_EXTENSION', -1)
				"""))
				.hasMessageContaining("ck_upload_audit_size");
		}
	}

	// --- 도구 ---------------------------------------------------------------

	private static void insertBlocked(String filename, String reason) throws SQLException {
		execute("""
			INSERT INTO upload_audit (original_filename, result, reason_code)
			VALUES ('%s', 'BLOCKED', '%s')
			""".formatted(filename, reason));
	}

	private static void insertPending(String filename, String key) throws SQLException {
		insertPending(filename, key, UUID.randomUUID());
	}

	private static void insertPending(String filename, String key, UUID fileId) throws SQLException {
		execute("""
			INSERT INTO upload_audit (original_filename, result, stored_key, file_id)
			VALUES ('%s', 'PENDING', '%s', '%s')
			""".formatted(filename, key, fileId));
	}

	private static void insertAllowed(String filename, String key) throws SQLException {
		insertAllowed(filename, key, UUID.randomUUID());
	}

	private static void insertAllowed(String filename, String key, UUID fileId) throws SQLException {
		execute("""
			INSERT INTO upload_audit (original_filename, result, stored_key, file_id)
			VALUES ('%s', 'ALLOWED', '%s', '%s')
			""".formatted(filename, key, fileId));
	}

	private static void insertError(String filename, String reason, String key) throws SQLException {
		execute("""
			INSERT INTO upload_audit (original_filename, result, reason_code, stored_key, file_id)
			VALUES ('%s', 'ERROR', '%s', '%s', '%s')
			""".formatted(filename, reason, key, UUID.randomUUID()));
	}

	private static void insertWithIp(String filename, String ip) throws SQLException {
		execute("""
			INSERT INTO upload_audit (original_filename, result, reason_code, client_ip)
			VALUES ('%s', 'BLOCKED', 'FILE_BLOCKED_EXTENSION', '%s')
			""".formatted(filename, ip));
	}

	private static String queryOne(String sql) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet rs = statement.executeQuery(sql)) {
			return rs.next() ? rs.getString(1) : null;
		}
	}

	private static void execute(String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}

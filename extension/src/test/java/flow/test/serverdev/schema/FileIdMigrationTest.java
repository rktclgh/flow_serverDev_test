package flow.test.serverdev.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import flow.test.serverdev.support.TestImages;

/**
 * V3 가 <b>기존 행 위에서</b> 도는지 본다. (SPEC §21.8)
 *
 * <p>지금 이 테이블은 어느 환경에서도 비어 있다. 그래서 "빈 테이블에 컬럼을 더하는" 것만
 * 확인하면 통과하는데, 그것은 마이그레이션이 아니라 DDL 이다. <b>행이 있어도 안전한가</b>가
 * 이 파일이 답하려는 질문이고, 그 답은 지금 확인해두지 않으면 운영에서 처음 확인된다.
 *
 * <p>특히 <b>{@code stored_key} 가 NULL 인 행</b>이 표적이다. 그 행까지 UUID 를 만들면
 * 4단계의 "{@code BLOCKED} → NULL" 검증과 충돌해 마이그레이션 자체가 실패한다 —
 * 배포 도중에, 되돌리기 어려운 시점에.
 *
 * <p>V2 까지만 적용한 상태에서 행을 넣고 V3 를 올린다. 스프링 컨텍스트는 필요 없다.
 */
@Testcontainers
@DisplayName("V3 — file_id 백필")
class FileIdMigrationTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestImages.POSTGRES);

	/** 키 끝에 UUID 가 붙은 실제 형태. 백필이 이 값을 그대로 살려야 한다. */
	private static final UUID ALLOWED_UUID = UUID.fromString("3f2a9c14-0b7d-4a51-9e88-2c1d5b6f7a30");
	private static final UUID ERROR_UUID = UUID.fromString("7c4e1b02-9d63-4f28-8a15-6e0b3c9d4f11");

	/**
	 * ★ 36자인데 UUID 가 아닌 키. <b>문자 집합만 보는 판별을 통과한다.</b>
	 *
	 * <p>하이픈만 36개도, 하이픈 없는 16진수 36자도 {@code [0-9a-f-]} 안에 있다. 통과시켜
	 * 놓고 {@code ::uuid} 로 캐스팅하면 거기서 터지고 <b>마이그레이션이 배포 도중 멈춘다.</b>
	 * V2 는 {@code stored_key} 에 임의의 문자열을 허용하므로, 이런 행은 옛 스키마에서
	 * 완전히 정상인 데이터다 — 지어낸 상황이 아니다.
	 */
	private static final String ALL_HYPHENS = "-".repeat(36);
	private static final String NO_HYPHENS = "abcdef".repeat(6);

	static Connection connection;

	@BeforeAll
	static void migrateToV2AndSeed() throws SQLException {
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.target("2")
			.load()
			.migrate();

		connection = DriverManager.getConnection(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

		execute("""
			INSERT INTO upload_audit (original_filename, result, reason_code, stored_key) VALUES
			  ('blocked.exe', 'BLOCKED', 'FILE_BLOCKED_EXTENSION', NULL),
			  ('no-key.pdf',  'ERROR',   'STORAGE_UNAVAILABLE',    NULL),
			  ('legacy.pdf',  'PENDING', NULL,                     '2026/08/29/legacy-key'),
			  ('ok.pdf',      'ALLOWED', NULL,                     '2026/08/29/%s'),
			  ('failed.pdf',  'ERROR',   'STORAGE_UNAVAILABLE',    '2026/08/29/%s'),
			  ('hyphens.pdf', 'ALLOWED', NULL,                     '2026/08/29/%s'),
			  ('nodash.pdf',  'ALLOWED', NULL,                     '2026/08/29/%s')
			""".formatted(ALLOWED_UUID, ERROR_UUID, ALL_HYPHENS, NO_HYPHENS));

		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
	}

	/**
	 * ★ 이 한 줄이 마이그레이션의 성패를 가른다. NULL 행에 UUID 를 만들면 같은 파일 4단계의
	 * CHECK 가 그 행을 거부해 배포가 중간에 멈춘다.
	 */
	@Test
	@DisplayName("stored_key 가 NULL 인 행은 건드리지 않는다")
	void leavesKeylessRowsAlone() throws SQLException {
		assertThat(fileIdOf("blocked.exe")).isNull();
		assertThat(fileIdOf("no-key.pdf")).isNull();
	}

	/**
	 * 키 끝의 UUID 가 곧 그 파일의 식별자다. 새로 만들어버리면 이미 저장된 객체와 행의
	 * {@code file_id} 가 어긋나 다운로드가 <b>조용히</b> 404 가 된다.
	 */
	@Test
	@DisplayName("키 끝의 UUID 를 그대로 살린다")
	void reusesUuidFromKey() throws SQLException {
		assertThat(fileIdOf("ok.pdf")).isEqualTo(ALLOWED_UUID.toString());
		assertThat(fileIdOf("failed.pdf")).isEqualTo(ERROR_UUID.toString());
	}

	/** UUID 를 못 읽어내는 옛 키에도 값은 있어야 한다 — PENDING 은 NOT NULL 이 제약이다. */
	@Test
	@DisplayName("UUID 를 읽어낼 수 없는 키에는 새 값을 만든다")
	void generatesForUnparseableKey() throws SQLException {
		assertThat(fileIdOf("legacy.pdf")).isNotNull();
	}

	/**
	 * ★ 외부 리뷰(Codex) P1 회귀. 판별이 문자 집합만 보면 캐스팅에서 터져
	 * <b>마이그레이션이 배포 도중 멈춘다.</b> 형식이 어긋나면 새 UUID 를 만드는 분기가
	 * 이미 있으므로, 판별만 정확해지면 결과는 안전하다.
	 *
	 * <p>이 테스트가 지키는 것은 값 하나가 아니라 <b>{@code @BeforeAll} 이 끝까지 도는가</b>
	 * 이기도 하다. 마이그레이션이 멈추면 이 클래스의 모든 테스트가 함께 죽는다.
	 */
	@Test
	@DisplayName("36자이지만 UUID 가 아닌 키에도 새 값을 만든다 — 캐스팅에서 멈추지 않는다")
	void generatesForUuidShapedButInvalidKey() throws SQLException {
		assertThat(fileIdOf("hyphens.pdf"))
			.as("하이픈 36개. 문자 집합만 보는 판별은 통과하지만 UUID 가 아니다")
			.isNotNull()
			.isNotEqualTo(ALL_HYPHENS);
		assertThat(fileIdOf("nodash.pdf"))
			.as("하이픈 없는 16진수 36자. 역시 문자 집합만 보면 통과한다")
			.isNotNull()
			.isNotEqualTo(NO_HYPHENS);
	}

	@Test
	@DisplayName("백필 결과에 중복이 없다")
	void noDuplicates() throws SQLException {
		assertThat(queryOne("""
			SELECT count(*) FROM (
			  SELECT file_id FROM upload_audit WHERE file_id IS NOT NULL
			  GROUP BY file_id HAVING count(*) > 1
			) d
			""")).isEqualTo("0");
	}

	private static String fileIdOf(String filename) throws SQLException {
		return queryOne("SELECT file_id FROM upload_audit WHERE original_filename = '%s'"
			.formatted(filename));
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

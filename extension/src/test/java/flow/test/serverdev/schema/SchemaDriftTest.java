package flow.test.serverdev.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import flow.test.serverdev.support.TestImages;

/**
 * {@code db/schema.sql} 과 Flyway 마이그레이션이 <b>같은 스키마를 만드는지</b> 검증한다.
 *
 * <p>schema.sql 은 실행되지 않는 문서다(Flyway 는 db/migration 만 읽는다).
 * 사람이 손으로 동기화해야 하므로 시간이 지나면 어긋난다.
 * 어긋난 문서는 없는 문서보다 나쁘다 — 읽는 사람이 틀린 정보를 믿게 된다.
 *
 * <p>같은 DB 안에 스키마 두 개를 만들어 한쪽엔 마이그레이션을, 다른 쪽엔 schema.sql 을
 * 적용한 뒤 컬럼·제약·트리거를 대조한다.
 */
@Testcontainers
@DisplayName("스키마 드리프트")
class SchemaDriftTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestImages.POSTGRES);

	private static final String MIGRATED = "mig";
	private static final String DOCUMENTED = "doc";

	@Test
	@DisplayName("schema.sql 과 마이그레이션이 동일한 컬럼·제약·트리거를 만든다")
	void 문서와_마이그레이션이_일치한다() throws Exception {
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.schemas(MIGRATED)
			.defaultSchema(MIGRATED)
			.locations("classpath:db/migration")
			.load()
			.migrate();

		try (Connection connection = DriverManager.getConnection(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

			execute(connection, "CREATE SCHEMA " + DOCUMENTED);
			execute(connection, "SET search_path = " + DOCUMENTED);
			execute(connection, readClasspath("db/schema.sql"));

			assertThat(columns(connection, DOCUMENTED))
				.as("컬럼 정의가 어긋났다. schema.sql 을 갱신하지 않았는지 확인하라")
				.isEqualTo(columns(connection, MIGRATED));

			assertThat(constraints(connection, DOCUMENTED))
				.as("제약이 어긋났다")
				.isEqualTo(constraints(connection, MIGRATED));

			assertThat(triggers(connection, DOCUMENTED))
				.as("트리거가 어긋났다")
				.isEqualTo(triggers(connection, MIGRATED));
		}
	}

	private static List<String> columns(Connection connection, String schema) throws SQLException {
		return query(connection, """
			SELECT column_name || ' ' || data_type
			       || ' nullable=' || is_nullable
			       || ' default=' || coalesce(column_default, '-')
			FROM information_schema.columns
			WHERE table_schema = '%s' AND table_name = 'blocked_extension'
			ORDER BY ordinal_position
			""".formatted(schema));
	}

	/** {@code pg_get_constraintdef} 로 정의까지 비교한다. 이름만 비교하면 내용 변경을 놓친다. */
	private static List<String> constraints(Connection connection, String schema) throws SQLException {
		return query(connection, """
			SELECT conname || ' :: ' || pg_get_constraintdef(oid)
			FROM pg_constraint
			WHERE conrelid = '%s.blocked_extension'::regclass
			ORDER BY conname
			""".formatted(schema));
	}

	private static List<String> triggers(Connection connection, String schema) throws SQLException {
		return query(connection, """
			SELECT tgname
			FROM pg_trigger
			WHERE tgrelid = '%s.blocked_extension'::regclass AND NOT tgisinternal
			ORDER BY tgname
			""".formatted(schema));
	}

	private static List<String> query(Connection connection, String sql) throws SQLException {
		List<String> rows = new ArrayList<>();
		try (Statement statement = connection.createStatement();
			 ResultSet rs = statement.executeQuery(sql)) {
			while (rs.next()) {
				rows.add(rs.getString(1));
			}
		}
		return rows;
	}

	private static void execute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static String readClasspath(String path) throws IOException {
		try (InputStream in = SchemaDriftTest.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IOException("클래스패스에서 찾을 수 없다: " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}

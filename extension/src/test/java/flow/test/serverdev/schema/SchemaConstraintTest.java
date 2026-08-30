package flow.test.serverdev.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * 스키마 제약이 <b>실제로 강제되는지</b> 검증한다. (SPEC §3)
 *
 * <p>H2 로는 검증할 수 없다. plpgsql 트리거와 INET 타입을 지원하지 않아
 * 마이그레이션을 따로 만들어야 하고, 그러면 테스트한 스키마와 배포 스키마가 달라진다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. 검증 대상이 애플리케이션이 아니라 <b>DDL</b> 이므로
 * Flyway 를 직접 실행하고 JDBC 로 확인한다. 빠르고, 실패 원인이 스키마로 좁혀진다.
 */
@Testcontainers
class SchemaConstraintTest {

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

	/** 각 테스트는 커스텀 확장자가 없는 상태에서 시작한다. 고정 7행은 트리거가 삭제를 막는다. */
	@BeforeEach
	void clearCustom() throws SQLException {
		execute("DELETE FROM blocked_extension WHERE type = 'CUSTOM'");
		execute("UPDATE blocked_extension SET is_blocked = FALSE WHERE type = 'FIXED'");
	}

	private static void execute(String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static long count(String where) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet rs = statement.executeQuery(
				 "SELECT count(*) FROM blocked_extension WHERE " + where)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static void insertCustom(String name, int slot) throws SQLException {
		execute("INSERT INTO blocked_extension (name, type, is_blocked, custom_slot) VALUES ('%s', 'CUSTOM', TRUE, %d)"
			.formatted(name, slot));
	}

	@Nested
	@DisplayName("고정 확장자 시드")
	class FixedSeed {

		@Test
		@DisplayName("고정 확장자 7개가 화면 순서대로 시드되고 기본값은 미체크다")
		void 시드_확인() throws SQLException {
			List<String> names = new ArrayList<>();
			try (Statement statement = connection.createStatement();
				 ResultSet rs = statement.executeQuery(
					 "SELECT name, is_blocked, custom_slot FROM blocked_extension WHERE type='FIXED' ORDER BY id")) {
				while (rs.next()) {
					names.add(rs.getString("name"));
					assertThat(rs.getBoolean("is_blocked")).as("기본값은 unCheck").isFalse();
					assertThat(rs.getObject("custom_slot")).as("FIXED 는 슬롯을 갖지 않는다").isNull();
				}
			}
			assertThat(names).containsExactly("bat", "cmd", "com", "cpl", "exe", "scr", "js");
		}

		@Test
		@DisplayName("고정 확장자는 삭제할 수 없다 — API 를 우회해도 트리거가 막는다")
		void 고정_삭제_방지() {
			assertThatThrownBy(() -> execute("DELETE FROM blocked_extension WHERE name='exe'"))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("FIXED");
		}
	}

	@Nested
	@DisplayName("이름 제약")
	class NameConstraint {

		@Test
		@DisplayName("정규화되지 않은 이름은 저장할 수 없다 — 앱 정규화의 최종 보증")
		void 형식_제약() {
			// 앱의 ExtensionNormalizer 에 버그가 생겨도 비정규 값이 DB 에 들어갈 수 없어야 한다.
			for (String invalid : List.of("EXE", "e x e", ".sh", "exe.", "한글", "a".repeat(21))) {
				assertThatThrownBy(() -> insertCustom(invalid, 1))
					.as("거부되어야 함: %s", invalid)
					.isInstanceOf(SQLException.class);
			}
		}

		@Test
		@DisplayName("이름은 중복될 수 없다 — 고정과 커스텀 사이에서도")
		void 이름_중복_방지() throws SQLException {
			insertCustom("sh", 1);
			assertThatThrownBy(() -> insertCustom("sh", 2)).isInstanceOf(SQLException.class);
			// 고정 확장자와 같은 이름을 커스텀으로 넣는 것도 막힌다
			assertThatThrownBy(() -> insertCustom("exe", 3)).isInstanceOf(SQLException.class);
		}
	}

	@Nested
	@DisplayName("커스텀 슬롯 — 200개 상한의 실제 보증")
	class CustomSlot {

		@Test
		@DisplayName("슬롯 범위를 벗어나면 저장할 수 없다")
		void 슬롯_범위() {
			assertThatThrownBy(() -> insertCustom("aa", 0)).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> insertCustom("bb", 201)).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> insertCustom("cc", -1)).isInstanceOf(SQLException.class);
		}

		@Test
		@DisplayName("같은 슬롯을 두 번 쓸 수 없다")
		void 슬롯_중복_방지() throws SQLException {
			insertCustom("aa", 5);
			assertThatThrownBy(() -> insertCustom("bb", 5)).isInstanceOf(SQLException.class);
		}

		@Test
		@DisplayName("★ 201번째 커스텀 확장자는 물리적으로 저장할 수 없다")
		void 상한_보증() throws SQLException {
			// 애플리케이션 count() 체크를 우회해 직접 INSERT 해도 막혀야 한다.
			// 이것이 성립해야 "앱 체크는 UX 용, DB 제약은 정합성용" 이라는 주장이 사실이 된다.
			for (int slot = 1; slot <= 200; slot++) {
				insertCustom("x%d".formatted(slot), slot);
			}
			assertThat(count("type='CUSTOM'")).isEqualTo(200);

			// 남은 슬롯이 없다 — 어떤 번호를 쓰든 실패한다
			assertThatThrownBy(() -> insertCustom("overflow", 201)).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> insertCustom("overflow", 200)).isInstanceOf(SQLException.class);
		}

		@Test
		@DisplayName("삭제한 슬롯은 재사용할 수 있다")
		void 슬롯_재사용() throws SQLException {
			insertCustom("aa", 7);
			execute("DELETE FROM blocked_extension WHERE name='aa'");
			insertCustom("bb", 7);
			assertThat(count("custom_slot=7")).isEqualTo(1);
		}

		@Test
		@DisplayName("FIXED 는 슬롯을 가질 수 없고 CUSTOM 은 반드시 가져야 한다")
		void 타입별_슬롯_규칙() {
			assertThatThrownBy(() -> execute(
				"INSERT INTO blocked_extension (name, type, is_blocked, custom_slot) VALUES ('zz','FIXED',FALSE,1)"))
				.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> execute(
				"INSERT INTO blocked_extension (name, type, is_blocked) VALUES ('zz','CUSTOM',TRUE)"))
				.isInstanceOf(SQLException.class);
		}

		@Test
		@DisplayName("CUSTOM 은 항상 차단 상태다 — 행의 존재가 곧 차단이다")
		void 커스텀은_항상_차단() {
			assertThatThrownBy(() -> execute(
				"INSERT INTO blocked_extension (name, type, is_blocked, custom_slot) VALUES ('zz','CUSTOM',FALSE,1)"))
				.isInstanceOf(SQLException.class);
		}
	}

	@Nested
	@DisplayName("우회 시도 차단")
	class BypassAttempts {

		// 아래 시나리오는 모두 외부 리뷰에서 지적받아 실제 Postgres 18 에서 재현한 뒤 막은 것들이다.
		// 삭제 트리거 하나로는 고정 확장자를 지킬 수 없었다.

		@Test
		@DisplayName("고정 확장자 이름이 아닌 행을 FIXED 로 추가할 수 없다")
		void 고정_임의_추가_차단() {
			// 이것이 막히지 않으면 type='FIXED' 로 임의의 행을 무한히 넣을 수 있다.
			assertThatThrownBy(() -> execute(
				"INSERT INTO blocked_extension (name, type, is_blocked) VALUES ('zzz','FIXED',TRUE)"))
				.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> execute(
				"INSERT INTO blocked_extension (name, type, is_blocked) "
					+ "SELECT 'x'||n, 'FIXED', TRUE FROM generate_series(1,201) g(n)"))
				.isInstanceOf(SQLException.class);
		}

		@Test
		@DisplayName("고정 확장자를 CUSTOM 으로 바꿔 삭제 트리거를 우회할 수 없다")
		void 타입_변경_우회_차단() {
			// 삭제 트리거는 OLD.type 만 본다. 먼저 CUSTOM 으로 바꾸면 그 다음 DELETE 가 통과한다.
			assertThatThrownBy(() -> execute(
				"UPDATE blocked_extension SET type='CUSTOM', custom_slot=1 WHERE name='exe'"))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("immutable");
		}

		@Test
		@DisplayName("고정 확장자의 이름을 바꿀 수 없다")
		void 이름_변경_차단() {
			assertThatThrownBy(() -> execute(
				"UPDATE blocked_extension SET name='zip' WHERE name='exe'"))
				.isInstanceOf(SQLException.class);
		}

		@Test
		@DisplayName("ON CONFLICT DO UPDATE 로도 고정 확장자를 변조할 수 없다")
		void upsert_변조_차단() {
			assertThatThrownBy(() -> execute(
				"INSERT INTO blocked_extension (name,type,is_blocked,custom_slot) "
					+ "VALUES ('bat','CUSTOM',TRUE,2) ON CONFLICT (name) DO UPDATE "
					+ "SET type=EXCLUDED.type, custom_slot=EXCLUDED.custom_slot"))
				.isInstanceOf(SQLException.class);
		}

		@Test
		@DisplayName("TRUNCATE 로 정책 전체를 지울 수 없다")
		void truncate_차단() {
			// TRUNCATE 는 행 단위 DELETE 트리거를 실행하지 않는다. 문 단위 트리거가 필요하다.
			assertThatThrownBy(() -> execute("TRUNCATE blocked_extension"))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("truncated");
		}

		@Test
		@DisplayName("고정 확장자의 is_blocked 토글은 정상 허용된다")
		void 정상_토글은_허용() throws SQLException {
			// 위 제약들이 정상 동작까지 막으면 안 된다.
			execute("UPDATE blocked_extension SET is_blocked = TRUE WHERE name='exe'");
			assertThat(count("name='exe' AND is_blocked = TRUE")).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("동시성")
	class Concurrency {

		/** 서로 다른 커넥션으로 동시에 실행한다. 단일 커넥션 순차 INSERT 로는 경쟁을 재현할 수 없다. */
		private List<Throwable> runConcurrently(int threads, String sqlTemplate) throws Exception {
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			CountDownLatch ready = new CountDownLatch(threads);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Throwable>> futures = new ArrayList<>();

			for (int i = 0; i < threads; i++) {
				final int index = i;
				futures.add(pool.submit(() -> {
					try (Connection own = DriverManager.getConnection(
						POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
						 Statement statement = own.createStatement()) {
						ready.countDown();
						start.await();
						statement.execute(sqlTemplate.formatted(index));
						return null;
					}
					catch (Throwable failure) {
						return failure;
					}
				}));
			}
			ready.await();
			start.countDown();

			List<Throwable> failures = new ArrayList<>();
			for (Future<Throwable> future : futures) {
				Throwable failure = future.get();
				if (failure != null) {
					failures.add(failure);
				}
			}
			pool.shutdown();
			return failures;
		}

		@Test
		@DisplayName("같은 이름을 동시에 추가하면 하나만 성공한다")
		void 이름_경쟁() throws Exception {
			int threads = 8;
			// 슬롯은 다르게, 이름은 같게 — UNIQUE(name) 만으로 경쟁을 판정한다.
			List<Throwable> failures = runConcurrently(threads,
				"INSERT INTO blocked_extension (name,type,is_blocked,custom_slot) "
					+ "VALUES ('dup','CUSTOM',TRUE,%d + 1)");

			assertThat(count("name='dup'")).as("한 건만 저장되어야 한다").isEqualTo(1);
			assertThat(failures).as("나머지는 제약 위반으로 실패해야 한다").hasSize(threads - 1);
		}

		@Test
		@DisplayName("★ 200개 경계에서 동시에 밀어넣어도 201개가 되지 않는다")
		void 상한_경쟁() throws Exception {
			// 199개를 채운 뒤 남은 슬롯 1개를 여러 스레드가 동시에 노린다.
			for (int slot = 1; slot <= 199; slot++) {
				insertCustom("y%d".formatted(slot), slot);
			}
			int threads = 8;
			List<Throwable> failures = runConcurrently(threads,
				"INSERT INTO blocked_extension (name,type,is_blocked,custom_slot) "
					+ "VALUES ('race%d','CUSTOM',TRUE,200)");

			assertThat(count("type='CUSTOM'")).as("200 을 넘을 수 없다").isEqualTo(200);
			assertThat(failures).hasSize(threads - 1);
		}
	}

	@Nested
	@DisplayName("타임스탬프")
	class Timestamps {

		@Test
		@DisplayName("UPDATE 시 updated_at 이 자동 갱신된다")
		void updated_at_갱신() throws SQLException {
			String before = single("SELECT updated_at FROM blocked_extension WHERE name='exe'");
			execute("UPDATE blocked_extension SET is_blocked = TRUE WHERE name='exe'");
			String after = single("SELECT updated_at FROM blocked_extension WHERE name='exe'");
			assertThat(after).as("트리거가 없으면 생성 시각 그대로 남는다").isNotEqualTo(before);
		}

		private String single(String sql) throws SQLException {
			try (Statement statement = connection.createStatement();
				 ResultSet rs = statement.executeQuery(sql)) {
				rs.next();
				return rs.getString(1);
			}
		}
	}
}

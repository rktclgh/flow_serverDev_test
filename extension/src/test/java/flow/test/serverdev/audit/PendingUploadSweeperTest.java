package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.support.IntegrationTest;

/**
 * PENDING 스위퍼. (SPEC §8.2)
 *
 * <p>스프링이 관리하는 {@link PendingUploadSweeper} 빈을 쓰지 않고 <b>직접 생성</b>한다.
 * 검증 대상이 이 클래스의 로직이라, 협력자({@link FakeObjectStorage})와 설정(임계·배치 크기)을
 * 테스트마다 바꿔 끼워야 하기 때문이다. 삭제 실패는 실물 MinIO 에 명령할 수 없고, 임계 경계는
 * 주기 설정을 테스트가 정해야 볼 수 있다. 나머지(리포지토리, DB)는 {@link IntegrationTest} 의
 * 실제 Postgres 를 그대로 쓴다 — 트리거와 CHECK 제약이 이 전이를 실제로 허용하는지까지 함께
 * 확인해야 한다. {@code MinioObjectStorageTest} 가 {@link flow.test.serverdev.storage.MinioObjectStorage}
 * 를 직접 생성해 쓰는 것과 같은 이유다.
 *
 * <p><b>배선 자체는 여기서 검증하지 않는다.</b> 빈 등록과 스케줄 등록은
 * {@link PendingUploadSweeperWiringTest}, 스케줄이 실제로 발화하는지는
 * {@link PendingUploadSweeperScheduleTest} 가 맡는다 — 이 셋을 한 파일에 섞으면
 * "로직은 맞는데 아무도 부르지 않는" 상태를 다시 놓친다.
 */
@DisplayName("PENDING 스위퍼")
class PendingUploadSweeperTest extends IntegrationTest {

	private static final Duration THRESHOLD = Duration.ofMinutes(10);

	@Autowired
	UploadAuditRepository repository;

	@Autowired
	JdbcTemplate jdbc;

	FakeObjectStorage storage;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM upload_audit");
		storage = new FakeObjectStorage();
	}

	private PendingUploadSweeper sweeper(int batchSize) {
		return new PendingUploadSweeper(repository, storage,
			new PendingUploadSweeperProperties(true, Duration.ofMinutes(5), THRESHOLD, batchSize));
	}

	/** 임계보다 오래된 시각. 트리거가 {@code occurred_at} 의 UPDATE 는 막지만 INSERT 는 막지 않으므로,
	 * 원시 JDBC INSERT 로 과거 시각을 직접 심는다 — 엔티티 경로로는 이 컬럼에 값을 넣을 수 없다. */
	private static OffsetDateTime stale() {
		return OffsetDateTime.now().minus(THRESHOLD).minusMinutes(1);
	}

	private static OffsetDateTime fresh() {
		return OffsetDateTime.now().minus(THRESHOLD).plusMinutes(1);
	}

	private long insertPending(OffsetDateTime occurredAt, String storedKey) {
		return jdbc.queryForObject(
			"INSERT INTO upload_audit (occurred_at, original_filename, size_bytes, result, stored_key) "
				+ "VALUES (?, 'test.pdf', 10, 'PENDING', ?) RETURNING id",
			Long.class, occurredAt, storedKey);
	}

	private long insertAllowed(OffsetDateTime occurredAt, String storedKey) {
		return jdbc.queryForObject(
			"INSERT INTO upload_audit (occurred_at, original_filename, size_bytes, result, stored_key) "
				+ "VALUES (?, 'test.pdf', 10, 'ALLOWED', ?) RETURNING id",
			Long.class, occurredAt, storedKey);
	}

	private long insertBlocked(OffsetDateTime occurredAt) {
		return jdbc.queryForObject(
			"INSERT INTO upload_audit (occurred_at, original_filename, size_bytes, result, reason_code) "
				+ "VALUES (?, 'test.exe', 10, 'BLOCKED', 'FILE_BLOCKED_EXTENSION') RETURNING id",
			Long.class, occurredAt);
	}

	private static String key() {
		return "2026/08/29/" + UUID.randomUUID();
	}

	@Nested
	@DisplayName("조회 — 임계 시간 경계")
	class ThresholdBoundary {

		@Test
		@DisplayName("임계보다 오래된 PENDING 행만 조회된다")
		void onlyStalePendingFound() {
			String staleKey = key();
			insertPending(stale(), staleKey);

			assertThat(repository.findStalePending(OffsetDateTime.now().minus(THRESHOLD)))
				.extracting(UploadAudit::storedKey)
				.containsExactly(staleKey);
		}

		@Test
		@DisplayName("★ 임계 이내의 PENDING 행은 건드리지 않는다 — 진행 중인 정상 업로드를 죽이지 않는다")
		void freshPendingIsUntouched() {
			String freshKey = key();
			insertPending(fresh(), freshKey);

			sweeper(100).sweep();

			UploadAudit audit = repository.findById(
				jdbc.queryForObject("SELECT id FROM upload_audit WHERE stored_key = ?", Long.class, freshKey))
				.orElseThrow();
			assertThat(audit.result()).isEqualTo(UploadResult.PENDING);
			assertThat(storage.deletedKeys()).isEmpty();
		}
	}

	@Nested
	@DisplayName("스윕")
	class Sweep {

		@Test
		@DisplayName("객체를 지우고 행을 ERROR/UPLOAD_ABANDONED 로 전이시킨다")
		void sweepsStaleRow() {
			String staleKey = key();
			long id = insertPending(stale(), staleKey);

			sweeper(100).sweep();

			assertThat(storage.deletedKeys()).containsExactly(staleKey);
			UploadAudit audit = repository.findById(id).orElseThrow();
			assertThat(audit.result()).isEqualTo(UploadResult.ERROR);
			assertThat(audit.reasonCode()).isEqualTo(PendingUploadSweeper.REASON_UPLOAD_ABANDONED);
			assertThat(audit.storedKey()).isEqualTo(staleKey);
		}

		@Test
		@DisplayName("객체 삭제가 실패하면 행은 PENDING 으로 남는다")
		void keepsRowPendingWhenDeleteFails() {
			String staleKey = key();
			long id = insertPending(stale(), staleKey);
			storage.failOn(staleKey);

			sweeper(100).sweep();

			UploadAudit audit = repository.findById(id).orElseThrow();
			assertThat(audit.result()).isEqualTo(UploadResult.PENDING);
			assertThat(audit.reasonCode()).isNull();
		}

		@Test
		@DisplayName("한 행이 실패해도 나머지 행은 계속 처리된다")
		void continuesAfterOneFailure() {
			String failingKey = key();
			String okKey = key();
			long failingId = insertPending(stale(), failingKey);
			long okId = insertPending(stale(), okKey);
			storage.failOn(failingKey);

			sweeper(100).sweep();

			assertThat(repository.findById(failingId).orElseThrow().result())
				.isEqualTo(UploadResult.PENDING);
			assertThat(repository.findById(okId).orElseThrow().result())
				.isEqualTo(UploadResult.ERROR);
			assertThat(storage.deletedKeys()).containsExactly(okKey);
		}

		@Test
		@DisplayName("ALLOWED·BLOCKED 행은 절대 건드리지 않는다")
		void neverTouchesConfirmedRows() {
			String allowedKey = key();
			long allowedId = insertAllowed(stale(), allowedKey);
			long blockedId = insertBlocked(stale());

			sweeper(100).sweep();

			assertThat(repository.findById(allowedId).orElseThrow().result())
				.isEqualTo(UploadResult.ALLOWED);
			assertThat(repository.findById(blockedId).orElseThrow().result())
				.isEqualTo(UploadResult.BLOCKED);
			assertThat(storage.deletedKeys()).doesNotContain(allowedKey);
		}

		@Test
		@DisplayName("batch-size 상한을 넘지 않는다")
		void respectsBatchSize() {
			insertPending(stale(), key());
			insertPending(stale(), key());
			insertPending(stale(), key());

			sweeper(2).sweep();

			long stillPending = jdbc.queryForObject(
				"SELECT count(*) FROM upload_audit WHERE result = 'PENDING'", Long.class);
			long nowError = jdbc.queryForObject(
				"SELECT count(*) FROM upload_audit WHERE result = 'ERROR'", Long.class);
			assertThat(nowError).isEqualTo(2);
			assertThat(stillPending).isEqualTo(1);
			assertThat(storage.deletedKeys()).hasSize(2);
		}
	}
}

package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import flow.test.serverdev.support.IntegrationTest;

/**
 * 스위퍼가 <b>실제로 주기적으로 돈다</b>는 것. (SPEC §8.2)
 *
 * <p>{@link PendingUploadSweeperTest} 는 {@code sweep()} 을 직접 불러 로직을 검증하고,
 * {@link PendingUploadSweeperWiringTest} 는 빈 등록과 스케줄 등록을 검증한다. 여기서는
 * <b>아무도 {@code sweep()} 을 부르지 않는다</b> — 행을 심어두고 기다리기만 한다.
 *
 * <p>이 테스트가 있어야 하는 이유는 이 기능이 죽어 있던 방식 그대로다. 구현도 있고 단위
 * 테스트도 초록이었지만 {@code @Scheduled} 는 한 번도 평가된 적이 없었다. 등록을 고치자마자
 * SpEL 빈 참조가 터졌다 — <b>등록되지 않은 스케줄은 틀려도 아무 소리를 내지 않는다.</b>
 * 협력자를 바꿔 끼우지 않고 실제 스케줄러·실제 MinIO·실제 Postgres 위에서 확인한다.
 *
 * <p>주기와 임계를 테스트용으로 줄인다. 기본값(5분/10분)으로는 관측이 불가능하다.
 * {@code @DirtiesContext} 로 클래스가 끝나면 컨텍스트를 닫아 <b>이 짧은 주기의 스케줄러가
 * 다음 테스트 클래스까지 살아남지 않게</b> 한다 — 컨테이너 하나를 모든 테스트가 공유하므로,
 * 살려두면 남의 {@code PENDING} 행을 지운다.
 */
@DisplayName("스위퍼 스케줄")
@TestPropertySource(properties = {
	"app.audit.sweeper.interval=200ms",
	"app.audit.sweeper.threshold=1s"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PendingUploadSweeperScheduleTest extends IntegrationTest {

	/** 200ms 주기에 비해 넉넉하다. 넘기면 스케줄러가 돌지 않는 것이다. */
	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@DisplayName("★ 아무도 sweep() 을 부르지 않아도 스케줄러가 오래된 PENDING 을 확정한다")
	void schedulerActuallyRuns() throws InterruptedException {
		jdbc.update("DELETE FROM upload_audit");
		String storedKey = "2026/08/30/" + UUID.randomUUID();
		long id = jdbc.queryForObject(
			"INSERT INTO upload_audit "
				+ "(occurred_at, original_filename, size_bytes, result, stored_key, file_id) "
				+ "VALUES (?, 'scheduled.pdf', 10, 'PENDING', ?, ?::uuid) RETURNING id",
			Long.class, OffsetDateTime.now().minusMinutes(1), storedKey,
			storedKey.substring(storedKey.lastIndexOf('/') + 1));

		String result = awaitConfirmation(id);

		assertThat(result)
			.as("PENDING 그대로면 @Scheduled 가 돌지 않은 것이다")
			.isEqualTo("ERROR");
		assertThat(jdbc.queryForObject(
			"SELECT reason_code FROM upload_audit WHERE id = ?", String.class, id))
			.isEqualTo(PendingUploadSweeper.REASON_UPLOAD_ABANDONED);
	}

	/** {@code PENDING} 을 벗어날 때까지, 또는 {@link #TIMEOUT} 까지 기다린다. */
	private String awaitConfirmation(long id) throws InterruptedException {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		String result;
		do {
			result = jdbc.queryForObject(
				"SELECT result FROM upload_audit WHERE id = ?", String.class, id);
			if (!"PENDING".equals(result)) {
				return result;
			}
			Thread.sleep(100);
		} while (System.nanoTime() < deadline);
		return result;
	}
}

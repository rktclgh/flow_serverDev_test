package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;

import flow.test.serverdev.support.IntegrationTest;

/**
 * 스위퍼 배선.
 *
 * <p><b>구현이 있는 것과 실제로 등록되어 도는 것은 다르다.</b> 이 스위퍼는 구현과 단위 테스트가
 * 모두 끝난 뒤에도 한동안 빈으로 등록되지 않은 채였고, 그동안 테스트는 290개가 전부 초록이었다.
 * 조건부 등록이 어긋나면 예외가 아니라 <b>조용한 미등록</b>이고, 스위퍼가 안 도는 것은
 * 고아 객체가 쌓여야 드러난다. 그때는 이미 늦다.
 *
 * <p>그래서 동작이 아니라 <b>존재</b>를 검사하는 테스트를 따로 둔다. 존재는 두 겹이다 —
 * 빈으로 등록되는 것과, 그 빈의 {@code sweep()} 이 <b>스케줄에 실제로 실려 있는 것</b>.
 * 앞의 결함을 고치자마자 뒤의 결함(SpEL 빈 참조)이 드러났으므로 둘을 따로 검사한다.
 * 스케줄이 실제로 발화하는지는 {@link PendingUploadSweeperScheduleTest} 가 확인한다.
 */
@DisplayName("스위퍼 배선")
class PendingUploadSweeperWiringTest extends IntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ScheduledTaskHolder scheduledTasks;

	@Autowired
	private PendingUploadSweeperProperties properties;

	@Test
	@DisplayName("PENDING 스위퍼가 빈으로 등록된다")
	void sweeperIsRegistered() {
		assertThat(context.getBeanNamesForType(PendingUploadSweeper.class))
			.as("등록되지 않으면 고아 객체가 영원히 남는다")
			.hasSize(1);
	}

	@Test
	@DisplayName("★ sweep() 이 고정 주기 작업으로 등록된다 — 빈만 있고 스케줄이 없으면 아무것도 안 한다")
	void sweepIsScheduled() {
		assertThat(sweepTasks())
			.as("@Scheduled 가 평가되지 않으면 여기가 비어 있다")
			.hasSize(1)
			.allSatisfy(task -> assertThat(task).isInstanceOf(FixedRateTask.class));
	}

	@Test
	@DisplayName("★ 스케줄 주기가 app.audit.sweeper.interval 과 같다 — 자리표시자와 프로퍼티가 같은 키를 본다")
	void scheduleIntervalMatchesProperties() {
		FixedRateTask task = (FixedRateTask) sweepTasks().getFirst();

		assertThat(properties.interval())
			.as("운영 기본값. application.yml 에 값이 없으면 이것이 쓰인다")
			.isEqualTo(Duration.ofMinutes(5));
		assertThat(task.getIntervalDuration())
			.as("두 값이 갈리면 설정을 바꿔도 주기는 그대로다 — 조용히")
			.isEqualTo(properties.interval());
		assertThat(task.getInitialDelayDuration())
			.as("첫 실행도 같은 키를 본다 — 한쪽만 자리표시자면 기동 직후 한 번 헛돈다")
			.isEqualTo(properties.interval());
	}

	/**
	 * {@code sweep()} 에 걸린 스케줄 작업.
	 *
	 * <p>{@code Runnable} 은 스프링이 {@code OutcomeTrackingRunnable} 로 한 번 감싸므로
	 * {@code instanceof ScheduledMethodRunnable} 로는 걸리지 않는다. 감싸도 살아남는 것은
	 * {@code toString()} 이 돌려주는 대상 메서드 이름이라 그것으로 고른다.
	 */
	private List<Task> sweepTasks() {
		String target = PendingUploadSweeper.class.getName() + ".sweep";
		return scheduledTasks.getScheduledTasks().stream()
			.map(ScheduledTask::getTask)
			.filter(task -> target.equals(task.toString()))
			.toList();
	}
}

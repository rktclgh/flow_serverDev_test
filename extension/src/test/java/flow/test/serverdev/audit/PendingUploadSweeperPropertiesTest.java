package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 스위퍼 설정의 <b>바인딩 시점 검증</b>. (외부 리뷰 P2)
 *
 * <p>잘못된 값이 통과하면 실패가 조용해진다 — {@code batchSize=0} 은 매 주기 예외라
 * 스위퍼가 영영 아무 일도 하지 않고, {@code threshold=0} 은 진행 중인 정상 업로드까지
 * 지운다. 둘 다 애플리케이션은 멀쩡히 떠 있는 채로 벌어진다. <b>기동을 막는 편이 낫다.</b>
 *
 * <p>컨테이너를 띄우지 않는다. 검증 대상이 바인딩과 제약 조건뿐이라
 * {@link ApplicationContextRunner} 로 프로퍼티 빈만 있는 최소 컨텍스트를 띄운다.
 */
@DisplayName("스위퍼 설정 검증")
class PendingUploadSweeperPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(Holder.class);

	@Configuration
	@EnableConfigurationProperties(PendingUploadSweeperProperties.class)
	static class Holder {
	}

	@Test
	@DisplayName("아무것도 설정하지 않으면 운영 기본값으로 바인딩된다")
	void bindsDefaults() {
		runner.run(context -> {
			PendingUploadSweeperProperties properties =
				context.getBean(PendingUploadSweeperProperties.class);
			assertThat(properties.enabled()).isTrue();
			assertThat(properties.interval()).isEqualTo(Duration.ofMinutes(5));
			assertThat(properties.threshold()).isEqualTo(Duration.ofMinutes(10));
			assertThat(properties.batchSize()).isEqualTo(100);
		});
	}

	@Test
	@DisplayName("정상 범위의 값은 그대로 통과한다 — 검증이 멀쩡한 설정을 막지 않는다")
	void acceptsValidOverrides() {
		runner.withPropertyValues(
			"app.audit.sweeper.interval=200ms",
			"app.audit.sweeper.threshold=1s",
			"app.audit.sweeper.batch-size=1")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Nested
	@DisplayName("★ 잘못된 값이면 기동하지 못한다")
	class RejectsInvalid {

		@Test
		@DisplayName("batch-size 가 0 이면 실패한다 — 통과시키면 매 주기 예외로 스위퍼가 조용히 죽는다")
		void rejectsZeroBatchSize() {
			assertFailsOn("app.audit.sweeper.batch-size=0", "batchSize");
		}

		@Test
		@DisplayName("batch-size 가 음수면 실패한다")
		void rejectsNegativeBatchSize() {
			assertFailsOn("app.audit.sweeper.batch-size=-1", "batchSize");
		}

		@Test
		@DisplayName("★ threshold 가 0 이면 실패한다 — 통과시키면 진행 중인 정상 업로드를 지운다")
		void rejectsZeroThreshold() {
			assertFailsOn("app.audit.sweeper.threshold=0s", "threshold");
		}

		@Test
		@DisplayName("threshold 가 음수면 실패한다 — 미래 시각보다 오래된 행을 찾게 된다")
		void rejectsNegativeThreshold() {
			assertFailsOn("app.audit.sweeper.threshold=-1m", "threshold");
		}

		@Test
		@DisplayName("interval 이 0 이면 실패한다")
		void rejectsZeroInterval() {
			assertFailsOn("app.audit.sweeper.interval=0s", "interval");
		}

		/**
		 * 실패 사유는 {@code ConfigurationPropertiesBindException} 의 <b>원인 사슬</b>에
		 * 들어 있다. 맨 위 메시지에는 접두사만 있고 어느 필드가 왜 걸렸는지가 없어,
		 * 사슬 전체에서 필드 이름과 제약 이름을 확인한다 — 기동을 막는 것만으로는 부족하고
		 * <b>무엇이 틀렸는지 로그로 알 수 있어야</b> 운영에서 고칠 수 있다.
		 */
		private void assertFailsOn(String property, String expectedField) {
			runner.withPropertyValues(property).run(context -> assertThat(context)
				.as("%s 로도 기동되면 그 결함은 운영에서야 드러난다", property)
				.hasFailed()
				.getFailure()
				.hasStackTraceContaining(expectedField)
				.hasStackTraceContaining("BindValidationException"));
		}
	}
}

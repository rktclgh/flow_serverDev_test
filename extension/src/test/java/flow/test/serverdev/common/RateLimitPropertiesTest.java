package flow.test.serverdev.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 운영 기본값을 못박는다. (SPEC §10.4)
 *
 * <p>통합 테스트는 한도를 크게 열어두고 돌기 때문에(그러지 않으면 업로드 테스트들이 서로의
 * 토큰을 빼앗는다) <b>실제 배포에 적용되는 값은 어느 통합 테스트도 확인하지 않는다.</b>
 * 여기서 확인하지 않으면 기본값이 조용히 바뀌어도 아무 테스트가 빨개지지 않는다.
 */
@DisplayName("속도 제한 설정")
class RateLimitPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(Holder.class);

	@Configuration
	@EnableConfigurationProperties(RateLimitProperties.class)
	static class Holder {
	}

	@Test
	@DisplayName("아무것도 설정하지 않으면 IP 당 60r/m · burst 10 이다")
	void bindsDefaults() {
		runner.run(context -> {
			RateLimitProperties properties = context.getBean(RateLimitProperties.class);
			assertThat(properties.enabled()).isTrue();
			assertThat(properties.perMinute()).isEqualTo(60);
			assertThat(properties.burst()).isEqualTo(10);
			assertThat(properties.generationInterval()).isEqualTo(Duration.ofMinutes(10));
			assertThat(properties.maxEntries()).isEqualTo(10_000);
		});
	}

	/**
	 * 0 이하가 통과하면 실패가 조용하다. {@code burst=0} 은 모든 업로드가 429 가 되고,
	 * {@code maxEntries=0} 은 매 요청 세대 교체라 제한이 사실상 사라진다.
	 * 둘 다 애플리케이션은 멀쩡히 떠 있는 채로 벌어진다 — 기동을 막는 편이 낫다.
	 */
	@Test
	@DisplayName("0 이하 값이면 기동하지 못한다")
	void rejectsNonPositive() {
		runner.withPropertyValues("app.rate-limit.burst=0")
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("app.rate-limit.per-minute=0")
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("app.rate-limit.max-entries=0")
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("app.rate-limit.generation-interval=0s")
			.run(context -> assertThat(context).hasFailed());
	}
}

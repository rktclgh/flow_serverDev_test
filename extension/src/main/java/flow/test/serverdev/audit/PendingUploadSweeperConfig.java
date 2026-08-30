package flow.test.serverdev.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import flow.test.serverdev.storage.ObjectStorage;

/**
 * PENDING 스위퍼 전용 설정. (SPEC §8.2)
 *
 * <p>{@code @EnableScheduling} 을 여기 두는 이유는 애플리케이션 메인 클래스를 건드리지
 * 않기 위함이다. 스케줄링은 이 기능만의 관심사이므로, 이 기능의 설정 클래스가 스스로
 * 활성화를 책임진다 — 나중에 이 기능을 통째로 들어내도 메인 클래스에는 흔적이 남지 않는다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PendingUploadSweeperProperties.class)
public class PendingUploadSweeperConfig {

	/**
	 * 스위퍼를 <b>명시적으로 조립한다.</b>
	 *
	 * <p>처음에는 {@code @Component} 에 {@code @ConditionalOnBean(ObjectStorage.class)} 를 붙였다.
	 * <b>실측 결과 그렇게 하면 {@link ObjectStorage} 빈이 있어도 등록되지 않는다.</b> 조건은
	 * 컴포넌트 스캔 시점에 평가되는데 그때 다른 {@code @Configuration} 의 {@code @Bean} 정의가
	 * 아직 등록되지 않았을 수 있어, 결과가 정의 순서에 좌우된다.
	 *
	 * <p>더 나쁜 것은 <b>실패하는 방향</b>이다. 조건이 어긋나면 예외가 아니라 <b>조용한 미등록</b>
	 * 이고, 스위퍼가 안 도는 것은 고아 객체가 쌓여야 드러난다. 그때는 이미 늦다.
	 *
	 * <p>{@code @Bean} 메서드로 만들면 파라미터가 곧 의존성 선언이라 순서 문제가 사라진다.
	 */
	@Bean
	@ConditionalOnProperty(prefix = "app.audit.sweeper", name = "enabled", havingValue = "true",
			matchIfMissing = true)
	public PendingUploadSweeper pendingUploadSweeper(UploadAuditRepository repository,
			ObjectStorage objectStorage, PendingUploadSweeperProperties properties) {
		return new PendingUploadSweeper(repository, objectStorage, properties);
	}
}

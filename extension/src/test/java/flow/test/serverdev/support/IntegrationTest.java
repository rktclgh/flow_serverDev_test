package flow.test.serverdev.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 스프링 컨텍스트가 필요한 테스트의 공통 기반.
 *
 * <p><b>싱글턴 컨테이너</b>를 쓴다. {@code @Container} 를 클래스마다 두면 테스트 클래스 수만큼
 * Postgres 가 뜨고 내려간다. 정적 초기화로 한 번만 띄우면 JVM 전체가 하나를 공유하고,
 * Ryuk 이 JVM 종료 시 회수한다. 디스크·시간 양쪽에서 이득이다.
 *
 * <p>컨텍스트 설정이 같아야 스프링 테스트 컨텍스트 캐시도 재사용되므로,
 * 프로퍼티는 여기서만 지정하고 하위 클래스는 추가하지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = "app.admin-token=" + IntegrationTest.ADMIN_TOKEN)
public abstract class IntegrationTest {

	/** 32자 이상 — 운영에서 요구하는 길이를 테스트에서도 지킨다. */
	public static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdef";

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestImages.POSTGRES);

	static {
		POSTGRES.start();
	}
}

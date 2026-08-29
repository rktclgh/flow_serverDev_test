package flow.test.serverdev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 애플리케이션 컨텍스트가 실제 스키마 위에서 기동하는지 검증한다.
 *
 * <p>이전 PR 에서는 DataSource 가 없어 비활성화되어 있었다.
 * P1(스키마)에서 Testcontainers 를 붙이며 활성화했다.
 *
 * <p>이 테스트가 보증하는 것은 다음 둘뿐이다.
 * <ul>
 *   <li>Flyway 마이그레이션이 실제 Postgres 에서 오류 없이 실행된다
 *   <li>컴포넌트 스캔과 빈 구성에 순환·누락이 없다
 * </ul>
 *
 * <p><b>보증하지 않는 것</b>: {@code ddl-auto=validate} 는 현재 JPA 엔티티가 하나도 없어
 * 검증할 대상이 없다. 엔티티가 추가되기 전까지 이 설정은 아무것도 확인하지 않는다.
 * CHECK 제약·트리거의 동작은 {@code SchemaConstraintTest} 가,
 * 문서와 마이그레이션의 일치는 {@code SchemaDriftTest} 가 담당한다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("애플리케이션 컨텍스트")
class ExtensionApplicationTests {

	/**
	 * {@code @ServiceConnection} 이 컨테이너의 접속 정보를 DataSource 에 자동 연결한다.
	 * H2 를 쓰지 않는 이유는 plpgsql 트리거와 정규식 CHECK 때문이다 — CONSIDERATIONS.md §참조.
	 */
	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

	@Test
	@DisplayName("실제 스키마 위에서 기동한다")
	void contextLoads() {
	}
}

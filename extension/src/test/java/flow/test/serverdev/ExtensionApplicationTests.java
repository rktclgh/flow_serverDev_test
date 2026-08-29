package flow.test.serverdev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import flow.test.serverdev.support.IntegrationTest;

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
 * <p>P2 에서 엔티티가 생기면서 {@code ddl-auto=validate} 가 비로소 <b>실제 검사</b>를 하게 됐다.
 * 그전까지는 검사 대상이 없어 이 테스트가 통과해도 스키마의 존재조차 보증하지 못했고,
 * 실제로 그 틈으로 Flyway 미실행 결함이 지나갔다({@code FlywayAutoConfigurationTest} 참조).
 *
 * <p><b>이제 MinIO 도 기동 전제가 되었다.</b> {@code MinioObjectStorage} 가 생성자에서 버킷을
 * 확인하므로 스토리지가 없으면 애플리케이션이 아예 뜨지 않는다. 그것이 의도한 동작이므로
 * 이 테스트도 두 컨테이너 위에서 돈다 — 기동 전제를 우회하면 검증 대상이 배포 대상과 달라진다.
 *
 * <p>역할 분담: 앱이 마이그레이션을 실행하는가는 {@code FlywayMigrationTest} 가,
 * CHECK 제약·트리거의 동작은 {@code SchemaConstraintTest} 가,
 * 문서와 마이그레이션의 일치는 {@code SchemaDriftTest} 가 담당한다.
 */
@DisplayName("애플리케이션 컨텍스트")
class ExtensionApplicationTests extends IntegrationTest {

	@Test
	@DisplayName("실제 스키마와 실제 스토리지 위에서 기동한다")
	void contextLoads() {
	}
}

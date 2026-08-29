package flow.test.serverdev.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.support.IntegrationTest;

/**
 * <b>애플리케이션이 스스로</b> 마이그레이션을 적용하는지 검증한다.
 *
 * <p>{@code SchemaConstraintTest} 와 {@code SchemaDriftTest} 는 Flyway 를 <b>직접 실행</b>한 뒤
 * 결과를 본다. 즉 "마이그레이션 파일이 올바른가"는 검증하지만 "앱이 그것을 실행하는가"는
 * 검증하지 않는다. 그 틈으로 실제 결함이 지나갔다({@link FlywayAutoConfigurationTest} 참조).
 *
 * <p>여기서는 앱이 만든 DataSource 를 그대로 쓴다. 검증 대상이 <b>기동 결과</b>이기 때문이다.
 *
 * <p>테스트가 하나뿐인 것은 의도다. 시드 내용·CHECK 제약·트리거는 {@code SchemaConstraintTest}
 * 가 이미 검증한다. 여기서 다시 확인하면 같은 사실을 두 곳에서 주장하게 되고,
 * 마이그레이션이 바뀔 때 고쳐야 할 곳만 늘어난다. 이 테스트가 유일하게 답하는 질문은
 * <b>"앱이 스스로 마이그레이션을 실행했는가"</b> 하나다.
 */
@DisplayName("애플리케이션 기동 시 마이그레이션")
class FlywayMigrationTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@DisplayName("기동만으로 V1 이 적용되어 있다")
	void migrationsAreApplied() {
		List<String> applied = jdbc.queryForList(
			"SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
			String.class);

		assertThat(applied).contains("1");
	}
}

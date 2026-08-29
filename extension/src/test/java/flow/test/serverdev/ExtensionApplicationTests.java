package flow.test.serverdev;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("""
	현재 이 테스트는 아무것도 보증하지 않는다.

	spring-boot-starter-data-jpa 를 의존하므로 컨텍스트 기동에 DataSource 가 필요하다.
	그런데 스키마 검증은 Testcontainers Postgres 로 해야 한다 —
	CHECK 정규식 제약과 plpgsql 트리거를 H2 로는 검증할 수 없다.

	P1(스키마) 작업에서 Testcontainers 기반으로 재작성하며 활성화한다.
	그때까지 비활성 상태임을 숨기지 않기 위해 사유를 남긴다.
	""")
class ExtensionApplicationTests {

	@Test
	void contextLoads() {
	}
}

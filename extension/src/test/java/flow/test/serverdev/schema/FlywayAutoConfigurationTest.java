package flow.test.serverdev.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flyway 자동설정이 <b>클래스패스에 등록되어 있는지</b> 검사한다.
 *
 * <p><b>스프링 컨텍스트를 띄우지 않는 것이 이 테스트의 핵심이다.</b>
 * 처음에는 {@code @Autowired(required = false) Flyway} 로 빈의 부재를 단언하려 했으나,
 * 뮤테이션 테스트로 확인해보니 그 방식은 진단을 주지 못했다. 의존성이 빠지면
 * 마이그레이션이 실행되지 않고 → {@code ddl-auto=validate} 가 먼저 실패하고 →
 * 컨텍스트 기동 자체가 무너져, 남는 메시지는 {@code Failed to load ApplicationContext} 뿐이다.
 * Flyway 는 어디에도 언급되지 않고 모든 통합 테스트가 함께 죽어 원인이 묻힌다.
 *
 * <p>그래서 컨텍스트에 의존하지 않는 검사로 바꿨다. 다른 모든 것이 무너져도 이 테스트만은
 * 정확한 이유를 말한다.
 *
 * <p><b>배경 — 왜 이런 가드가 필요한가</b>: Spring Boot 4 는 자동설정을 기술별 모듈로 분리했다.
 * {@code FlywayAutoConfiguration} 은 {@code org.springframework.boot:spring-boot-flyway} 에 있고,
 * Boot 3 처럼 {@code flyway-core} 만 선언하면 자동설정 클래스가 아예 존재하지 않아
 * 마이그레이션이 <b>조용히</b> 실행되지 않는다. 빌드도 되고 앱도 뜨고 에러 로그도 없다.
 * 스키마만 없다.
 *
 * <p>클래스 이름을 직접 확인하지 않고 <b>Boot 이 실제로 읽는 등록 파일</b>을 확인한다.
 * 모듈 내부에서 클래스 이름이 바뀌어도 통과하고, 모듈이 빠지면 실패한다 — 검사하려는 것이
 * "특정 클래스의 존재"가 아니라 "자동설정으로 등록되는가"이기 때문이다.
 */
@DisplayName("Flyway 자동설정 등록")
class FlywayAutoConfigurationTest {

	/** Spring Boot 이 자동설정 후보를 읽어들이는 파일. 클래스패스의 모든 jar 에서 수집된다. */
	private static final String IMPORTS =
		"META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	@Test
	@DisplayName("자동설정 목록에 Flyway 가 있다 — 없으면 spring-boot-flyway 의존성 누락이다")
	void flywayAutoConfigurationIsRegistered() throws IOException {
		List<String> registered = readAutoConfigurationImports();

		assertThat(registered)
			.as("Flyway 자동설정이 클래스패스에 없다. Boot 4 는 자동설정을 기술별 모듈로 나눴으므로 "
				+ "org.springframework.boot:spring-boot-flyway 를 의존성에 추가해야 한다. "
				+ "flyway-core 만으로는 마이그레이션이 실행되지 않는다")
			.anyMatch(entry -> entry.contains("Flyway"));
	}

	private List<String> readAutoConfigurationImports() throws IOException {
		List<String> entries = new ArrayList<>();
		Enumeration<URL> resources = getClass().getClassLoader().getResources(IMPORTS);

		while (resources.hasMoreElements()) {
			try (InputStream stream = resources.nextElement().openStream()) {
				new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
					.map(String::trim)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.forEach(entries::add);
			}
		}
		return entries;
	}
}

plugins {
	java
	id("org.springframework.boot") version "4.0.8"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "flow.test"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")

	// 인증 목적이 아니다. 보안 헤더와 STATELESS 세션 정책을 앱 레벨에 고정하기 위함.
	// nginx 에만 헤더를 두면 `docker compose up` 으로 띄운 환경에서는 전부 사라진다.
	implementation("org.springframework.boot:spring-boot-starter-security")

	// 스키마는 Flyway가 SSOT. ddl-auto=validate 고정 (SPEC §12)
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	// 오브젝트 스토리지 (SPEC §9) — Boot BOM이 관리하지 않으므로 버전 명시
	implementation("io.minio:minio:9.0.3")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

	// CHECK 정규식·INET·plpgsql 트리거는 H2로 검증 불가 (SPEC §13)
	// Spring Boot 4 의 BOM 은 Testcontainers 버전을 관리하지 않는다(3.x 와 달라진 점).
	// 버전을 개별로 박지 않고 BOM 을 import 해 모듈 간 버전 정합을 유지한다.
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testImplementation("org.testcontainers:minio")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

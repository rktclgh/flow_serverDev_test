package flow.test.serverdev.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

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

	/** 테스트가 쓰는 버킷. 애플리케이션이 뜨기 전에 존재해야 한다. */
	public static final String BUCKET = "extguard-test";

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestImages.POSTGRES);

	/**
	 * MinIO 도 통합 테스트의 전제다. {@code MinioObjectStorage} 가 생성자에서 버킷을 확인하므로
	 * <b>MinIO 없이는 애플리케이션이 아예 뜨지 않는다.</b> 그것이 의도한 동작이고, 테스트가
	 * 그 전제를 우회하면 테스트하는 것과 배포하는 것이 달라진다.
	 */
	static final MinIOContainer MINIO = new MinIOContainer(TestImages.MINIO);

	static {
		POSTGRES.start();
		MINIO.start();
		createBucket();
	}

	private static void createBucket() {
		try (MinioClient client = MinioClient.builder()
			.endpoint(MINIO.getS3URL())
			.credentials(MINIO.getUserName(), MINIO.getPassword())
			.build()) {
			client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
		} catch (Exception e) {
			throw new IllegalStateException("테스트 버킷 생성 실패", e);
		}
	}

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.endpoint", MINIO::getS3URL);
		registry.add("app.storage.access-key", MINIO::getUserName);
		registry.add("app.storage.secret-key", MINIO::getPassword);
		registry.add("app.storage.bucket", () -> BUCKET);
	}
}

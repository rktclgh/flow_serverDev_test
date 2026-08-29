package flow.test.serverdev.storage;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;

/**
 * 스토리지 배선. (SPEC §9, §17 P4)
 *
 * <p>구현 클래스들에 {@code @Component} 를 붙이지 않고 여기서 명시적으로 조립한다.
 * {@link MinioObjectStorage} 는 생성자에서 버킷 존재를 확인하므로 <b>빈 생성이 곧 기동 검사</b>이고,
 * 그 순서를 컴포넌트 스캔에 맡기지 않는 편이 읽기에도 낫다.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

	@Bean
	public MinioClient minioClient(StorageProperties properties) {
		return MinioClient.builder()
			.endpoint(properties.endpoint())
			.credentials(properties.accessKey(), properties.secretKey())
			.build();
	}

	/**
	 * 버킷이 없으면 여기서 예외가 나고 <b>애플리케이션이 뜨지 않는다.</b> 첫 업로드 때까지
	 * 미루면 그 시점의 사용자가 500 을 받는다.
	 */
	@Bean
	public ObjectStorage objectStorage(MinioClient minioClient, StorageProperties properties) {
		return new MinioObjectStorage(minioClient, properties.bucket());
	}

	/**
	 * 저장 키의 날짜 프리픽스가 이 시계의 시간대를 따른다.
	 *
	 * <p>기본 시간대를 쓰되, <b>이미지에 {@code ENV TZ=Asia/Seoul} 을 박아 그 값을 고정한다.</b>
	 * 실측해보면 {@code eclipse-temurin:21-jre-alpine} 은 {@code TZ} 가 없을 때 UTC 로 뜬다.
	 * 호스트가 서울이어도 그렇다 — 컨테이너는 호스트 시간대를 물려받지 않는다.
	 * 그대로 두면 한국 시간 오전 0~9시 업로드가 전날 프리픽스로, 그것도 조용히 들어간다.
	 *
	 * <p>전용 설정 키를 따로 만들지 않은 이유는 시간대가 닿는 곳이 <b>키의 날짜 프리픽스 하나</b>
	 * 뿐이기 때문이다. {@code occurred_at} 은 {@code TIMESTAMPTZ} 라 절대 시각을 저장하고,
	 * MinIO 는 시간대라는 개념 자체가 없다. 설정 축을 늘리면 잘못 설정될 자리만 는다.
	 */
	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}

	@Bean
	public StorageKeyGenerator storageKeyGenerator(Clock clock) {
		return new StorageKeyGenerator(clock);
	}
}

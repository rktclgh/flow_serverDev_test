package flow.test.serverdev.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 접속 설정. (SPEC §12)
 *
 * @param endpoint  S3 API 주소
 * @param accessKey 접근 키
 * @param secretKey 비밀 키
 * @param bucket    업로드 전용 private 버킷. <b>없으면 기동이 실패한다</b> — 자동 생성하지 않는다
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String bucket) {
}

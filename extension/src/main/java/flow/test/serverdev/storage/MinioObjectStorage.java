package flow.test.serverdev.storage;

import java.io.InputStream;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;

/**
 * {@link ObjectStorage} 의 MinIO 구현. (SPEC §9)
 *
 * <p><b>버킷이 없으면 생성하지 않고 기동을 멈춘다.</b> 자동 생성은 두 가지를 망가뜨린다 —
 * 버킷 이름에 오타가 나면 조용히 새 버킷이 생겨 그때까지의 파일과 단절되고, 새로 만든 버킷의
 * 공개 정책을 코드가 보증해야 한다. 이 서비스의 MinIO 는 S3 API 가 외부에 노출돼 있어
 * public-read 버킷이 하나 생기면 그대로 저장형 XSS 다. 그 책임을 코드가 지지 않는다.
 */
public class MinioObjectStorage implements ObjectStorage {

	/**
	 * 저장 시 강제하는 Content-Type. 클라이언트가 보낸 값은 보지 않는다.
	 * 이것이 다운로드의 {@code nosniff} 와 짝이 되어야 브라우저가 렌더링하지 않는다.
	 *
	 * <p><b>배포 대상 서버에서 직접 측정했다</b>(같은 릴리스 {@code RELEASE.2025-09-07T16-13-09Z}).
	 *
	 * <table>
	 *   <caption>실 서버에 넣어보고 저장된 값</caption>
	 *   <tr><th>보낸 것</th><th>저장된 Content-Type</th></tr>
	 *   <tr><td>이 구현 (octet-stream 강제)</td><td>{@code application/octet-stream}</td></tr>
	 *   <tr><td>헤더 미전송</td><td>{@code application/octet-stream} (서버 기본값)</td></tr>
	 *   <tr><td>{@code text/html}</td><td><b>{@code text/html}</b></td></tr>
	 * </table>
	 *
	 * <p>세 번째 줄이 핵심이다 — <b>MinIO 는 강제하지 않고 보낸 값을 그대로 저장한다.</b>
	 * octet-stream 이 되는 유일한 이유는 우리가 그렇게 보내거나 아무것도 안 보내기 때문이다.
	 * 그래서 안전은 두 겹으로 성립한다: 이 계약에 Content-Type 인자가 <b>없어서</b> 외부 값이
	 * 흘러들 수 없고, 그 위에 이 줄이 값을 명시한다.
	 *
	 * <p>인자가 없는 한 이 줄을 지워도 결과가 같아 <b>뮤테이션으로는 죽지 않는다.</b> 그래도
	 * 지우지 않는 이유는, 죽지 않는 이유가 "이 줄이 무의미해서" 가 아니라 "다른 방어가 같은
	 * 것을 막고 있어서" 이기 때문이다. 겹친 방어 중 하나를 관측 불가라는 이유로 걷어내면
	 * 남은 하나가 무너질 때 아무것도 남지 않는다.
	 */
	static final String FORCED_CONTENT_TYPE = "application/octet-stream";

	/**
	 * 객체가 없을 때 S3 가 돌려주는 코드. <b>이 문자열을 아는 것은 이 클래스뿐이어야 한다.</b>
	 * 상위 계층이 알게 되면 스토리지 구현을 바꾸는 순간 그 조건문들이 전부 틀린다.
	 */
	private static final String NO_SUCH_KEY = "NoSuchKey";

	/** 크기를 알고 있으므로 파트 분할을 맡기지 않는다. */
	private static final long SINGLE_PART = -1L;

	private final MinioClient client;
	private final String bucket;

	public MinioObjectStorage(MinioClient client, String bucket) {
		this.client = client;
		this.bucket = bucket;
		requireBucket();
	}

	/**
	 * 버킷이 있는지 확인한다. 없으면 생성자에서 터지므로 <b>빈 생성이 실패하고 기동이 멈춘다.</b>
	 * 첫 업로드가 들어올 때까지 기다렸다가 알게 되면, 그 시점의 사용자가 500 을 받는다.
	 */
	private void requireBucket() {
		boolean exists;
		try {
			exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
		} catch (MinioException e) {
			throw new StorageException("버킷 확인에 실패했습니다: " + bucket, e);
		}
		if (!exists) {
			throw new StorageException(
				"버킷이 없습니다: " + bucket + ". 자동 생성하지 않습니다 — 공개 정책을 코드가 보증할 수 없으므로 "
					+ "운영에서 private 으로 미리 만들어야 합니다.");
		}
	}

	@Override
	public void store(StorageKey key, InputStream content, long size) {
		try {
			client.putObject(PutObjectArgs.builder()
				.bucket(bucket)
				.object(key.value())
				.stream(content, size, SINGLE_PART)
				.contentType(FORCED_CONTENT_TYPE)
				.build());
		} catch (ErrorResponseException e) {
			// 서버가 요청을 받고 거부했다. 객체가 없는 것이 확실하다.
			throw new StorageException("객체를 저장하지 못했습니다: " + key.value(), e);
		} catch (MinioException e) {
			// 전송·타임아웃 실패. 서버에 닿았는지조차 모른다.
			throw new StorageOutcomeUnknownException(
				"객체 저장 결과를 확인할 수 없습니다: " + key.value(), e);
		}
	}

	@Override
	public InputStream load(String key) {
		try {
			return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
		} catch (ErrorResponseException e) {
			if (isNoSuchKey(e)) {
				throw new StorageObjectNotFoundException(key);
			}
			throw new StorageException("객체를 읽지 못했습니다: " + key, e);
		} catch (MinioException e) {
			throw new StorageException("객체를 읽지 못했습니다: " + key, e);
		}
	}

	@Override
	public void delete(String key) {
		try {
			client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
		} catch (MinioException e) {
			throw new StorageException("객체를 지우지 못했습니다: " + key, e);
		}
	}

	private static boolean isNoSuchKey(ErrorResponseException e) {
		return e.errorResponse() != null && NO_SUCH_KEY.equals(e.errorResponse().code());
	}
}

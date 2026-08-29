package flow.test.serverdev.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;

import flow.test.serverdev.support.TestImages;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.StatObjectArgs;

/**
 * MinIO 저장소. (SPEC §9)
 *
 * <p>실제 MinIO 를 띄워 검증한다. 모킹하면 <b>이 클래스가 보증한다고 주장하는 것들</b> —
 * 저장된 객체의 Content-Type, 없는 버킷에서의 기동 실패, 없는 키 삭제의 멱등성 — 이 전부
 * 내가 모킹으로 지어낸 동작이 되고, 검증 대상이 MinIO 가 아니라 내 상상이 된다.
 */
@DisplayName("MinIO 저장소")
class MinioObjectStorageTest {

	private static final String BUCKET = "extguard-test";

	static final MinIOContainer MINIO = new MinIOContainer(TestImages.MINIO);

	static {
		MINIO.start();
	}

	private static MinioClient client;
	private static MinioObjectStorage storage;

	@BeforeAll
	static void prepareBucket() throws Exception {
		client = MinioClient.builder()
			.endpoint(MINIO.getS3URL())
			.credentials(MINIO.getUserName(), MINIO.getPassword())
			.build();
		client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
		storage = new MinioObjectStorage(client, BUCKET);
	}

	private static StorageKey key() {
		UUID id = UUID.randomUUID();
		return new StorageKey(id, "2026/08/29/" + id);
	}

	private static byte[] binary() {
		// 0x00 을 섞는다. 텍스트로 다뤄지면 여기서 잘린다.
		return new byte[] { 0x4D, 0x5A, 0x00, 0x00, 0x7F, (byte) 0xFF, 0x00, 0x41 };
	}

	private static void store(StorageKey key, byte[] content) {
		storage.store(key, new ByteArrayInputStream(content), content.length);
	}

	private static byte[] load(String key) throws IOException {
		try (InputStream in = storage.load(key)) {
			return in.readAllBytes();
		}
	}

	@Nested
	@DisplayName("저장과 조회")
	class RoundTrip {

		@Test
		@DisplayName("올린 바이트를 그대로 돌려준다")
		void bytesSurvive() throws Exception {
			StorageKey key = key();
			byte[] content = binary();

			store(key, content);

			assertThat(load(key.value())).isEqualTo(content);
		}

		@Test
		@DisplayName("키가 다르면 서로 덮어쓰지 않는다")
		void keysAreIndependent() throws Exception {
			StorageKey first = key();
			StorageKey second = key();

			store(first, "첫 번째".getBytes(StandardCharsets.UTF_8));
			store(second, "두 번째".getBytes(StandardCharsets.UTF_8));

			assertThat(load(first.value())).asString(StandardCharsets.UTF_8).isEqualTo("첫 번째");
			assertThat(load(second.value())).asString(StandardCharsets.UTF_8).isEqualTo("두 번째");
		}

		@Test
		@DisplayName("없는 키를 읽으면 NotFound 로 구분된다")
		void loadMissing() {
			assertThatThrownBy(() -> storage.load("2026/08/29/" + UUID.randomUUID()))
				.isInstanceOf(StorageObjectNotFoundException.class);
		}

		/**
		 * 판별이 <b>한쪽으로만</b> 맞으면 소용없다. {@code isNoSuchKey} 가 무조건 참을 돌려줘도
		 * 위 테스트는 통과하고, 그러면 권한 오류·버킷 소실까지 "파일이 없습니다" 로 보고된다.
		 * 진짜 다른 S3 오류(NoSuchBucket)를 만들어 반대 방향을 고정한다.
		 */
		@Test
		@DisplayName("다른 S3 오류를 NotFound 로 오인하지 않는다")
		void otherErrorsAreNotNotFound() throws Exception {
			String doomed = "extguard-doomed";
			client.makeBucket(MakeBucketArgs.builder().bucket(doomed).build());
			MinioObjectStorage onDoomed = new MinioObjectStorage(client, doomed);
			client.removeBucket(RemoveBucketArgs.builder().bucket(doomed).build());

			assertThatThrownBy(() -> onDoomed.load("2026/08/29/" + UUID.randomUUID()))
				.isInstanceOf(StorageException.class)
				.isNotInstanceOf(StorageObjectNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("Content-Type 강제")
	class ContentType {

		private String storedContentType(String key) throws Exception {
			return client.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build()).contentType();
		}

		@Test
		@DisplayName("무엇을 올리든 application/octet-stream 으로 저장된다")
		void forcedOnStoredObject() throws Exception {
			StorageKey key = key();

			// 브라우저가 렌더링할 수 있는 내용. 그럼에도 octet-stream 이어야 한다.
			store(key, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

			assertThat(storedContentType(key.value())).isEqualTo("application/octet-stream");
		}

		/**
		 * ★ <b>이 테스트도, 위의 테스트도 우리 코드를 방어하지 못한다.</b> 그 사실을 숨기지 않는다.
		 *
		 * <p>뮤테이션으로 확인했다 — 구현에서 {@code contentType(...)} 을 지워도 둘 다 통과한다.
		 * 측정해보니 {@code PutObjectArgs.contentType()} 은 미지정 시 {@code null} 이고
		 * (SDK 는 키 이름에서 형식을 추측하지 않는다), 헤더가 없으면 <b>MinIO 서버가</b>
		 * {@code application/octet-stream} 으로 채운다. 우리가 보내든 안 보내든 결과가 같다.
		 *
		 * <p><b>배포 대상 서버에 직접 넣어 확인했다.</b> 같은 릴리스에서 {@code text/html} 을 보내면
		 * {@code text/html} 로 저장된다 — 서버는 강제하지 않고 보낸 값을 그대로 쓴다. 우리가
		 * octet-stream 을 얻는 것은 서버가 우리를 지켜줘서가 아니라 <b>이 계약에 Content-Type
		 * 인자가 없어서</b>다.
		 *
		 * <p>그래서 이 테스트가 지키는 것은 우리 코드가 아니라 <b>우리가 기대는 외부 동작</b>이다.
		 * 그 기대가 깨지는 날 여기서 알게 된다. 확장자가 붙은 키로도 확인하는 것은 키 형식이
		 * 나중에 바뀌는 경우를 위해서다.
		 */
		@Test
		@DisplayName("키에 확장자가 있어도 octet-stream 으로 저장된다")
		void notGuessedFromKey() throws Exception {
			UUID id = UUID.randomUUID();
			StorageKey withExtension = new StorageKey(id, "2026/08/29/" + id + ".html");

			store(withExtension, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

			assertThat(storedContentType(withExtension.value())).isEqualTo("application/octet-stream");
		}
	}

	@Nested
	@DisplayName("삭제")
	class Delete {

		@Test
		@DisplayName("지우면 더 이상 읽히지 않는다")
		void deleteRemoves() throws Exception {
			StorageKey key = key();
			store(key, binary());

			storage.delete(key.value());

			assertThatThrownBy(() -> storage.load(key.value()))
				.isInstanceOf(StorageObjectNotFoundException.class);
		}

		@Test
		@DisplayName("없는 키를 지우는 것은 실패가 아니다 — 보상 삭제가 재시도돼도 안전하다")
		void deleteMissingIsNotFailure() {
			assertThatCode(() -> storage.delete("2026/08/29/" + UUID.randomUUID()))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("버킷")
	class Bucket {

		@Test
		@DisplayName("버킷이 없으면 만들지 않고 생성 시점에 실패한다 — 빈으로 등록되면 기동이 멈춘다")
		void missingBucketFailsFast() {
			assertThatThrownBy(() -> new MinioObjectStorage(client, "no-such-bucket"))
				.isInstanceOf(StorageException.class)
				.hasMessageContaining("no-such-bucket");
		}
	}
}

package flow.test.serverdev.audit;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import flow.test.serverdev.storage.ObjectStorage;
import flow.test.serverdev.storage.StorageException;
import flow.test.serverdev.storage.StorageKey;

/**
 * {@link PendingUploadSweeper} 테스트 전용 {@link ObjectStorage} 더블.
 *
 * <p>실제 MinIO 를 쓰지 않는 이유는 <b>"삭제가 실패했다"를 명령대로 재현해야</b>
 * 하기 때문이다. 실제 MinIO 는 호출자가 원하는 순간에 실패해주지 않는다 — 삭제
 * 실패 시 행이 {@code PENDING} 으로 남는다는 계약(SPEC §8.2)을 검증하려면 실패를
 * 마음대로 켤 수 있는 더블이 필요하다. {@link ObjectStorage} 자체의 계약(Content-Type
 * 강제, 없는 버킷에서의 기동 실패 등)은 {@code MinioObjectStorageTest} 가 이미
 * 실물로 검증한다 — 여기서 다시 검증할 대상이 아니다.
 */
final class FakeObjectStorage implements ObjectStorage {

	private final List<String> deletedKeys = new ArrayList<>();
	private final Set<String> failingKeys = new HashSet<>();

	@Override
	public void store(StorageKey key, InputStream content, long size) {
		throw new UnsupportedOperationException("스위퍼 테스트는 store 를 쓰지 않는다");
	}

	@Override
	public InputStream load(String key) {
		throw new UnsupportedOperationException("스위퍼 테스트는 load 를 쓰지 않는다");
	}

	@Override
	public void delete(String key) {
		if (failingKeys.contains(key)) {
			throw new StorageException("테스트 강제 실패: " + key);
		}
		deletedKeys.add(key);
	}

	/** 이후 이 키에 대한 {@link #delete} 호출을 실패시킨다. */
	void failOn(String key) {
		failingKeys.add(key);
	}

	List<String> deletedKeys() {
		return deletedKeys;
	}
}

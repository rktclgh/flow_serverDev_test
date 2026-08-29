package flow.test.serverdev.upload;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import flow.test.serverdev.storage.ObjectStorage;
import flow.test.serverdev.storage.StorageException;
import flow.test.serverdev.storage.StorageKey;
import flow.test.serverdev.storage.StorageObjectNotFoundException;
import flow.test.serverdev.storage.StorageOutcomeUnknownException;

/**
 * 명령대로 실패하는 저장소. 모킹 프레임워크 대신 <b>직접 쓴 구현</b>이다.
 *
 * <p>실 MinIO 로는 "저장은 됐는데 응답을 못 받았다" 를 재현할 수 없다. 그 경로가 이 패킷에서
 * 가장 중요한 분기(확정 금지)이므로 재현 수단이 필요하다.
 */
class FakeObjectStorage implements ObjectStorage {

	private final Map<String, byte[]> objects = new LinkedHashMap<>();
	private RuntimeException failure;

	void failWith(RuntimeException failure) {
		this.failure = failure;
	}

	static StorageException definiteFailure() {
		return new StorageException("서버가 거부했다");
	}

	static StorageOutcomeUnknownException unknownOutcome() {
		return new StorageOutcomeUnknownException("응답을 받지 못했다", new IOException("timeout"));
	}

	boolean isEmpty() {
		return objects.isEmpty();
	}

	boolean contains(String key) {
		return objects.containsKey(key);
	}

	@Override
	public void store(StorageKey key, InputStream content, long size) {
		if (failure != null) {
			throw failure;
		}
		try {
			objects.put(key.value(), content.readAllBytes());
		}
		catch (IOException e) {
			throw new StorageException("읽기 실패", e);
		}
	}

	@Override
	public InputStream load(String key) {
		byte[] found = objects.get(key);
		if (found == null) {
			throw new StorageObjectNotFoundException(key);
		}
		return new ByteArrayInputStream(found);
	}

	@Override
	public void delete(String key) {
		objects.remove(key);
	}
}

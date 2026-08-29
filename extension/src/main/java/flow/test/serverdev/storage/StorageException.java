package flow.test.serverdev.storage;

/**
 * 스토리지 조작이 실패했다. (SPEC §9)
 *
 * <p>이 프로젝트는 <b>사용자 입력 거부를 예외가 아니라 값으로</b> 표현한다. 차단 확장자는
 * 예외 상황이 아니라 정상적인 판정 결과이기 때문이다. 여기는 그 반대다 — MinIO 가 응답하지
 * 않는 것은 판정이 아니라 진짜 예외 상황이고, 호출자가 무시하면 안 된다.
 *
 * <p>HTTP 상태나 {@code ErrorCode} 로의 매핑은 <b>여기서 하지 않는다.</b> 스토리지는 자기가
 * 실패했다는 사실만 말하고, 그것을 사용자에게 어떻게 보여줄지는 업로드 서비스가 정한다.
 */
public class StorageException extends RuntimeException {

	public StorageException(String message, Throwable cause) {
		super(message, cause);
	}

	public StorageException(String message) {
		super(message);
	}
}

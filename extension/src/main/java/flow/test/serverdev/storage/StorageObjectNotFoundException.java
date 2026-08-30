package flow.test.serverdev.storage;

/**
 * 그 키에 해당하는 객체가 없다.
 *
 * <p>일반 실패와 구분하는 이유는 <b>다운로드가 404 를 내려야</b> 하기 때문이다(SPEC §7.6).
 * 구분을 여기서 하는 것이 중요하다 — 서비스가 S3 에러 코드({@code NoSuchKey})를 직접 보게 하면
 * 스토리지 구현이 상위 계층으로 새고, MinIO 를 다른 것으로 바꾸는 순간 그 조건문이 전부 틀린다.
 */
public class StorageObjectNotFoundException extends StorageException {

	public StorageObjectNotFoundException(String key) {
		super("저장된 객체가 없습니다: " + key);
	}
}

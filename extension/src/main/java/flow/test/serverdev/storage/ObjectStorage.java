package flow.test.serverdev.storage;

import java.io.InputStream;

/**
 * 업로드된 파일의 저장소. (SPEC §9)
 *
 * <p><b>이 계약에 무엇이 없는지가 설계의 핵심이다.</b>
 *
 * <ul>
 *   <li><b>파일명을 받지 않는다.</b> 받을 자리가 없으면 신뢰할 일도 없다. 원본 파일명은
 *       {@code upload_audit.original_filename} 에 데이터로만 남는다.</li>
 *   <li><b>Content-Type 을 받지 않는다.</b> 클라이언트가 보낸 값을 그대로 저장하면
 *       MIME 스푸핑이 그대로 통과한다. 구현이 {@code application/octet-stream} 으로 고정한다.</li>
 *   <li><b>키를 문자열로 받지 않는다.</b> {@link StorageKey} 는 생성기만 만들 수 있으므로
 *       호출자가 경로를 지어낼 재료 자체가 없다.</li>
 * </ul>
 *
 * <p><b>키를 인자로 받는 이유</b>는 감사 기록이 2단계이기 때문이다(SPEC §8.2). PUT 보다 먼저
 * {@code PENDING} 행에 {@code stored_key} 를 적어야 하므로, 키는 저장 시점이 아니라
 * 그보다 앞에서 만들어져 있어야 한다. 스토리지가 키를 만들어 돌려주는 형태로는 이 순서가 성립하지 않는다.
 */
public interface ObjectStorage {

	/**
	 * 객체를 저장한다.
	 *
	 * @param content 저장할 바이트. 이 메서드가 소비하며 닫지는 않는다
	 * @param size    바이트 수. 미리 알려주면 파트 분할 없이 한 번에 올린다
	 * @throws StorageException 저장에 실패했을 때
	 */
	void store(StorageKey key, InputStream content, long size);

	/**
	 * 객체를 읽는다. 호출자가 닫아야 한다.
	 *
	 * @throws StorageObjectNotFoundException 그 키의 객체가 없을 때
	 * @throws StorageException               그 밖의 실패
	 */
	InputStream load(String key);

	/**
	 * 객체를 지운다. <b>없는 키를 지우는 것은 실패가 아니다</b>(S3 semantics).
	 *
	 * <p>용도는 감사 3단계의 보상 삭제다. 보상 자체도 실패할 수 있으므로 호출자는 이것을
	 * best-effort 로 다뤄야 하며, 실패해도 행이 {@code PENDING} 으로 남아 탐지된다.
	 *
	 * @throws StorageException 삭제에 실패했을 때
	 */
	void delete(String key);
}

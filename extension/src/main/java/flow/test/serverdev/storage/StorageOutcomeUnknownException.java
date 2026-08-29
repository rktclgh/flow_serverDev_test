package flow.test.serverdev.storage;

/**
 * 저장을 시도했으나 <b>성공했는지 알 수 없다.</b> (SPEC §21.6)
 *
 * <p>전송이 끊기거나 응답을 못 받은 경우다. 서버에 요청이 닿았는지조차 모른다.
 * {@link StorageException} 과 구분하는 이유는 <b>호출자가 해야 할 일이 정반대</b>이기 때문이다.
 *
 * <ul>
 *   <li>{@link StorageException} — 서버가 받고 거부했다. 객체가 없는 것이 <b>확실</b>하므로
 *       감사를 {@code ERROR} 로 확정해도 된다.</li>
 *   <li>이 예외 — <b>모른다.</b> {@code ERROR} 로 확정하면 실제로 저장된 객체가
 *       {@code PENDING} 대상에서 사라져 아무도 정리하지 못하는 고아가 된다.
 *       확정하지 않고 {@code PENDING} 으로 남겨 스위퍼에 맡긴다.</li>
 * </ul>
 *
 * <p>둘을 한 타입으로 묶으면 "저장은 됐는데 응답만 못 받은" 경우를 실패로 확정하게 된다.
 */
public class StorageOutcomeUnknownException extends StorageException {

	public StorageOutcomeUnknownException(String message, Throwable cause) {
		super(message, cause);
	}
}

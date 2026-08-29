package flow.test.serverdev.policy;

/**
 * 확장자 정규화 결과. (SPEC §4)
 *
 * <p>실패를 예외가 아니라 값으로 표현한다. 정규화 실패는 예외적 상황이 아니라
 * 사용자 입력에 대한 정상적인 판정 결과이며, 호출부가 사유별로 다른 응답을 내려야 하기 때문이다.
 * sealed 로 선언해 switch 에서 누락을 컴파일러가 잡도록 한다.
 */
public sealed interface NormalizeResult {

	/** 정규화 성공. {@code value} 는 항상 {@code ^[a-z0-9]{1,20}$} 를 만족한다. */
	record Ok(String value) implements NormalizeResult {}

	/** 정규화 거부. */
	record Rejected(RejectReason reason) implements NormalizeResult {}
}

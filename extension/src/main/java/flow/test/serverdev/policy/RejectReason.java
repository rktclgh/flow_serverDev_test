package flow.test.serverdev.policy;

/**
 * 확장자 정규화 거부 사유. (SPEC §4)
 *
 * <p>길이 초과를 형식 오류와 구분하는 이유는 사용자에게 다른 안내를 주기 위함이다.
 * "20자를 넘었습니다"와 "허용되지 않는 문자가 있습니다"는 대응 방법이 다르다.
 */
public enum RejectReason {

	/** 빈 값, 공백만 있는 값, 점만 있는 값 */
	EMPTY,

	/** 정규화 후 20자 초과 */
	TOO_LONG,

	/** {@code ^[a-z0-9]{1,20}$} 에 매치되지 않음 */
	INVALID_CHARACTER
}

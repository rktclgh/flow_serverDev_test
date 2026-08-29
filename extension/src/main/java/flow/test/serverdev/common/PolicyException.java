package flow.test.serverdev.common;

import java.util.Map;

/**
 * 정책 도메인 규칙 위반. 사용자에게 그대로 설명할 수 있는 실패다.
 *
 * <p>스택트레이스를 만들지 않는다({@code super(message, null, false, false)}).
 * 이 예외는 흐름 제어가 아니라 <b>판정 결과의 전달</b>이고, 정상 동작 중에
 * 반복적으로 발생하므로 스택 수집 비용이 낭비다.
 */
public class PolicyException extends RuntimeException {

	private final transient ErrorCode errorCode;
	private final transient Map<String, Object> detail;

	public PolicyException(ErrorCode errorCode, String message) {
		this(errorCode, message, Map.of());
	}

	public PolicyException(ErrorCode errorCode, String message, Map<String, Object> detail) {
		super(message, null, false, false);
		this.errorCode = errorCode;
		this.detail = Map.copyOf(detail);
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

	/** 무엇이 / 어느 정책에 걸렸는지. 화면이 사유를 구체적으로 보여주기 위한 부가 정보다. */
	public Map<String, Object> detail() {
		return detail;
	}
}

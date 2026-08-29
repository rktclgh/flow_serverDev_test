package flow.test.serverdev.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 실패 응답의 형태. (SPEC §7.7)
 *
 * <p>과제 요구 "명확한 사유와 함께 거부"에 대응해 <b>무엇이 / 왜 / 어느 정책에</b> 걸렸는지
 * 셋을 담는다. {@code detail} 은 비어 있으면 직렬화에서 빠진다 — 의미 없는 빈 객체를
 * 클라이언트가 분기 대상으로 오해하지 않도록.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(String code, String message, Map<String, Object> detail) {

	public static ApiErrorResponse of(PolicyException exception) {
		return new ApiErrorResponse(
			exception.errorCode().name(), exception.getMessage(), exception.detail());
	}

	public static ApiErrorResponse of(ErrorCode code, String message) {
		return new ApiErrorResponse(code.name(), message, Map.of());
	}

	/**
	 * {@code detail} 까지 담는다. 판정 결과를 <b>값으로</b> 돌려주는 경로(업로드 거부)는 예외를
	 * 거치지 않으므로 별도 팩토리가 필요하다.
	 */
	public static ApiErrorResponse of(ErrorCode code, String message, Map<String, Object> detail) {
		return new ApiErrorResponse(code.name(), message, Map.copyOf(detail));
	}

}

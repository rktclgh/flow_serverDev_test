package flow.test.serverdev.common;

import org.springframework.http.HttpStatus;

/**
 * 애플리케이션이 반환하는 실패 코드. (SPEC §7.7)
 *
 * <p><b>상태 코드보다 이 코드가 실질이다.</b> 클라이언트는 HTTP 상태로 큰 분기만 하고
 * 화면 처리는 이 코드로 한다. 상태 코드는 프록시·서버가 만들어낼 수도 있어
 * 애플리케이션의 판정과 1:1로 대응하지 않기 때문이다.
 *
 * <p>메시지를 여기에 두지 않고 throw 지점에서 만드는 이유는 대부분의 메시지가
 * 확장자 이름을 포함해야 하기 때문이다. 코드 → 사용자 문구 매핑은 프론트가 담당한다(SPEC §19).
 */
public enum ErrorCode {

	/** 정규화 실패 — 허용되지 않는 문자가 섞여 있다. */
	EXT_INVALID_FORMAT(HttpStatus.BAD_REQUEST),

	/** 정규화 후 20자 초과. 형식 오류와 구분해야 사용자가 대응할 수 있다. */
	EXT_TOO_LONG(HttpStatus.BAD_REQUEST),

	/** 지목한 확장자가 없다. 정규화조차 되지 않는 이름도 여기에 해당한다. */
	EXT_NOT_FOUND(HttpStatus.NOT_FOUND),

	/** 이미 커스텀 목록에 있다. */
	EXT_DUPLICATE(HttpStatus.CONFLICT),

	/** 고정 확장자와 이름이 겹친다. 거부하되 어디서 처리하면 되는지 안내한다. */
	EXT_FIXED_CONFLICT(HttpStatus.CONFLICT),

	/** 고정 확장자는 삭제할 수 없다. 체크 해제로 대신한다. */
	EXT_FIXED_NOT_DELETABLE(HttpStatus.CONFLICT),

	/** 커스텀 상한(200)에 도달했다. */
	EXT_LIMIT_EXCEEDED(HttpStatus.CONFLICT),

	/**
	 * 슬롯 경합. advisory lock 을 잡지 않는 경로(수동 SQL·배치)가 그 사이에 슬롯을 가져간 경우다.
	 * 재시도하면 대개 성공하므로 상한 도달(EXT_LIMIT_EXCEEDED)과 구분한다.
	 */
	EXT_SLOT_CONFLICT(HttpStatus.CONFLICT),

	/** 요청 본문이 없거나 필수 필드가 빠졌다. */
	REQUEST_INVALID(HttpStatus.BAD_REQUEST),

	/** 그런 경로가 없다. 애플리케이션이 만드는 실패가 아니라 스프링이 판정한 것이다. */
	NOT_FOUND(HttpStatus.NOT_FOUND),

	/** 경로는 있으나 그 메서드를 지원하지 않는다. */
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

	/** 요청의 Content-Type 을 처리할 수 없다. */
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

	/** 예상하지 못한 서버 오류. 내부 정보를 담지 않는다. */
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}

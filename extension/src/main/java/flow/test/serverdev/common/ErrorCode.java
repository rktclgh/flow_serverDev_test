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

	/** 업로드할 파일 파트가 없거나 이름이 {@code file} 이 아니다. (SPEC §21.7) */
	FILE_REQUIRED(HttpStatus.BAD_REQUEST),

	/** 한 요청에 파일이 둘 이상이다. 정상 클라이언트는 만들지 않는다 — 파싱 비용 공격 방어. */
	FILE_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST),

	/** 파일명에 쓸 수 없는 문자가 있다. 널바이트·양방향 제어·제어문자를 하나로 접는다. */
	FILE_NAME_INVALID(HttpStatus.BAD_REQUEST),

	/** 파일명이 255자를 넘는다. <b>사용자가 고칠 수 있는</b> 유일한 파일명 사유라 따로 둔다. */
	FILE_NAME_TOO_LONG(HttpStatus.BAD_REQUEST),

	/** 0바이트 파일. */
	FILE_EMPTY(HttpStatus.BAD_REQUEST),

	/**
	 * 확장자가 없다. 400이 아니라 422인 이유는 요청이 잘못된 것이 아니라 <b>정책이 거부</b>한
	 * 것이기 때문이다. 설정({@code app.policy.allow-extensionless})으로 허용할 수 있다.
	 */
	FILE_EXTENSION_MISSING(HttpStatus.UNPROCESSABLE_ENTITY),

	/** 차단 목록에 있는 확장자다. 이 서비스의 존재 이유에 해당하는 판정이다. */
	FILE_BLOCKED_EXTENSION(HttpStatus.UNPROCESSABLE_ENTITY),

	/** 이름이 아니라 <b>내용</b>이 실행 파일이다. 확장자를 바꿔도 통과하지 못한다. */
	FILE_EXECUTABLE_CONTENT(HttpStatus.UNPROCESSABLE_ENTITY),

	/**
	 * 크기 상한 초과. multipart 파싱 중에 터지므로 <b>파일명을 알 수 없고, 그래서 감사하지 않는다</b>
	 * (SPEC §21.2). {@code original_filename} 은 NOT NULL 이라 자리표시자를 넣어야 하는데
	 * 그러면 감사 데이터가 오염된다.
	 */
	FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),

	/**
	 * 요청이 너무 잦다. (SPEC §10.4)
	 *
	 * <p>{@code Retry-After} 헤더를 함께 보낸다 — 언제 다시 오면 되는지는 서버만 안다.
	 * 화면은 이것을 <b>실패로 표시하지 않고</b> 그만큼 기다렸다 자동 재시도한다(SPEC §19).
	 *
	 * <p>이 판정은 <b>감사하지 않는다</b>(SPEC §21.2). 정책 판정에 도달하지 않아 기록할 내용이
	 * 없고, 기록하면 요청만으로 감사 테이블을 부풀릴 수 있다.
	 */
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

	/** 스토리지가 저장을 거부했다. 객체가 없는 것이 확실하므로 다시 시도하면 된다. */
	STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

	/**
	 * 저장됐는지 <b>알 수 없다</b>. (SPEC §21.6)
	 *
	 * <p>{@link #STORAGE_UNAVAILABLE} 과 나누는 이유는 <b>재시도 정책이 반대</b>이기 때문이다.
	 * 이 코드는 자동 재시도 대상이 아니다 — 첫 요청이 실제로 성공했을 수 있고, 재시도하면
	 * 같은 파일이 새 UUID 로 한 번 더 올라간다. 사용자가 확인하고 판단해야 한다.
	 */
	UPLOAD_OUTCOME_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE),

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

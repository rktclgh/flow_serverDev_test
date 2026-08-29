package flow.test.serverdev.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import flow.test.serverdev.storage.StorageException;
import flow.test.serverdev.storage.StorageOutcomeUnknownException;

/**
 * 예외를 {@link ApiErrorResponse} 로 변환하는 단일 지점.
 *
 * <p><b>{@code ResponseEntityExceptionHandler} 를 상속하는 것이 핵심이다.</b>
 * 상속하지 않고 {@code @ExceptionHandler(Exception.class)} 만 두면, 스프링이 던지는 표준 MVC
 * 예외가 {@code DefaultHandlerExceptionResolver} 에 닿기 전에 catch-all 이 먼저 가로챈다.
 * 실측 결과 다음이 전부 <b>500</b> 으로 나갔다.
 *
 * <table>
 *   <caption>상속 전 실측</caption>
 *   <tr><th>요청</th><th>실제</th><th>올바른 값</th></tr>
 *   <tr><td>없는 경로 GET</td><td>500</td><td>404</td></tr>
 *   <tr><td>지원하지 않는 메서드</td><td>500</td><td>405</td></tr>
 *   <tr><td>Content-Type 누락·불일치</td><td>500</td><td>415</td></tr>
 * </table>
 *
 * <p>상태 코드가 틀린 것보다 <b>부수 피해가 더 크다</b>. 공개 배포에서는 봇이 없는 경로를
 * 끊임없이 긁는데, 그때마다 {@code log.error} 로 스택트레이스가 쌓이고 404 가 5xx 로 집계되어
 * 오류율 지표가 거짓이 된다. 진짜 장애가 났을 때 그것을 알아볼 수 없게 된다.
 *
 * <p>상속하면 상태 코드 판정은 스프링이 하고, 우리는 <b>본문의 형태만</b> 책임진다.
 * 표준 예외의 기본 본문은 RFC 9457 {@code ProblemDetail} 이라 우리 계약({@code code}/{@code message})과
 * 다르므로 {@link #handleExceptionInternal} 한 곳에서 갈아끼운다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** 애플리케이션이 스스로 내린 판정. 사용자에게 그대로 설명할 수 있는 실패다. */
	/**
	 * 결과를 알 수 없는 저장 실패. <b>{@link StorageException} 보다 먼저</b> 선언돼 있어야 하는 것이
	 * 아니라, 스프링이 가장 구체적인 핸들러를 고르므로 하위 타입인 이것이 선택된다.
	 */
	@ExceptionHandler(StorageOutcomeUnknownException.class)
	ResponseEntity<ApiErrorResponse> handleStorageUnknown(StorageOutcomeUnknownException exception) {
		log.warn("저장 결과 불명", exception);
		return ResponseEntity.status(ErrorCode.UPLOAD_OUTCOME_UNKNOWN.status())
			.body(ApiErrorResponse.of(ErrorCode.UPLOAD_OUTCOME_UNKNOWN, "저장됐는지 확인할 수 없습니다."));
	}

	@ExceptionHandler(StorageException.class)
	ResponseEntity<ApiErrorResponse> handleStorage(StorageException exception) {
		log.error("스토리지 실패", exception);
		return ResponseEntity.status(ErrorCode.STORAGE_UNAVAILABLE.status())
			.body(ApiErrorResponse.of(ErrorCode.STORAGE_UNAVAILABLE, "저장소에 연결하지 못했습니다."));
	}

	@ExceptionHandler(PolicyException.class)
	ResponseEntity<ApiErrorResponse> handlePolicy(PolicyException exception) {
		return ResponseEntity.status(exception.errorCode().status())
			.body(ApiErrorResponse.of(exception));
	}

	/**
	 * 예상하지 못한 예외. 메시지를 <b>그대로 흘리지 않는다</b> — 예외 메시지에는 SQL·경로·
	 * 라이브러리 구성이 담기는 경우가 많다. 로그에는 남기고 응답에는 고정 문구만 보낸다.
	 *
	 * <p>표준 MVC 예외는 상위 클래스가 더 구체적으로 처리하므로 여기로 오지 않는다.
	 * 여기 걸리는 것은 <b>정말로 예상하지 못한 것</b>뿐이며, 그래서 {@code log.error} 가 의미를 갖는다.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
		log.error("처리되지 않은 예외", exception);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
			.body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR, "요청을 처리하지 못했습니다."));
	}

	/**
	 * 상위 클래스가 처리한 모든 표준 예외가 여기로 모인다. 상태 코드는 이미 정해져 있고,
	 * 우리는 본문만 우리 계약으로 바꾼다.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		ApiErrorResponse error = new ApiErrorResponse(
			codeFor(status).name(), messageFor(exception, status), Map.of());

		return super.handleExceptionInternal(exception, error, headers, status, request);
	}

	private ErrorCode codeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 404 -> ErrorCode.NOT_FOUND;
			case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
			case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
			// 크기 초과. 별도 @ExceptionHandler 를 두지 않는 이유는 상위 클래스가 이미
			// MaxUploadSizeExceededException 을 처리하기 때문이다 — 같은 예외에 핸들러를 하나 더
			// 선언하면 매핑이 모호해져 컨텍스트가 아예 뜨지 않는다. 상태 코드로 받는다.
			//
			// 이 응답이 브라우저까지 도착하는 것은 server.tomcat.max-swallow-size: -1 덕분이다.
			// 그 설정이 없으면 남은 바이트를 읽지 않고 커넥션을 끊어 ERR_CONNECTION_RESET 이 뜬다.
			case 413 -> ErrorCode.FILE_TOO_LARGE;
			// 명시하지 않은 상태는 계열로 판단한다. 새 상태가 생겨도 5xx 가 400 으로
			// 둔갑하지 않도록, 모르는 것은 계열을 따라간다.
			default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.REQUEST_INVALID;
		};
	}

	private String messageFor(Exception exception, HttpStatusCode status) {
		if (exception instanceof MethodArgumentNotValidException invalid) {
			String field = invalid.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(error -> error.getField())
				.orElse("request");
			return "요청 값이 올바르지 않습니다: " + field;
		}
		if (exception instanceof HttpMessageNotReadableException) {
			// 파서 메시지는 내부 구조(클래스명·필드 경로)를 드러내므로 쓰지 않는다.
			return "요청 본문을 읽을 수 없습니다.";
		}
		return switch (status.value()) {
			case 404 -> "요청하신 경로를 찾을 수 없습니다.";
			case 405 -> "지원하지 않는 요청 방식입니다.";
			case 415 -> "지원하지 않는 형식입니다.";
			default -> status.is5xxServerError()
				? "요청을 처리하지 못했습니다."
				: "요청이 올바르지 않습니다.";
		};
	}
}

package flow.test.serverdev.policy;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 정책 변경 기록. (SPEC §7.0)
 *
 * <p>별도 테이블을 두지 않고 로그로 남긴다 — 근거는 SPEC §1 Non-Goals.
 * 다만 "누가 언제 무엇을 바꿨는가"는 남아야 하므로 구조화 로그로 고정 형식을 쓴다.
 *
 * <p><b>서비스가 아니라 여기에 둔 이유</b>: client IP 는 HTTP 계층의 정보다.
 * 이것을 서비스 시그니처에 끌고 들어가면 도메인 규칙이 전송 계층을 알게 된다.
 *
 * <p>성공한 변경만 기록한다. 실패는 {@code GlobalExceptionHandler} 의 관심사다.
 */
@Component
public class PolicyAuditLogger {

	private static final Logger log = LoggerFactory.getLogger(PolicyAuditLogger.class);

	public void changed(String action, String extension, HttpServletRequest request) {
		// forward-headers-strategy=native 설정으로 Tomcat 이 X-Forwarded-For 를 반영한 값이다.
		log.info("policy_change action={} extension={} client_ip={} occurred_at={}",
			action, extension, request.getRemoteAddr(), Instant.now());
	}
}

package flow.test.serverdev.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 정책 <b>변경</b> API 를 관리 토큰으로 보호한다. (SPEC §7.0)
 *
 * <p>보호 대상은 {@code /api/extensions} 이하의 쓰기 요청뿐이다.
 * 정책 조회와 파일 업로드는 공개다 — 과제의 "누구나 접속 가능" 요구가 그쪽에 걸린다.
 *
 * <p>무인증으로 두면 누구나 {@code exe} 차단을 해제할 수 있어 정책 자체가 무의미해진다.
 * 반면 계정 체계를 도입하는 것은 과제 범위 밖이므로, 토큰 하나로 관리 기능만 분리했다.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

	private static final String HEADER = "X-Admin-Token";
	private static final String PROTECTED_PREFIX = "/api/extensions";

	private final byte[] expectedToken;
	private final boolean configured;

	public AdminTokenFilter(@Value("${app.admin-token:}") String adminToken) {
		this.configured = adminToken != null && !adminToken.isBlank();
		this.expectedToken = configured ? adminToken.getBytes(StandardCharsets.UTF_8) : new byte[0];
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// 조회(GET)는 화면 렌더링에 필요하므로 공개
		if (HttpMethod.GET.matches(request.getMethod())) {
			return true;
		}
		return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		// 토큰이 설정되지 않았으면 정책 변경을 전부 거부한다(fail-closed).
		// 설정 누락이 "인증 없음"으로 조용히 전락하는 것을 막기 위함이다.
		if (!configured) {
			reject(response, "ADMIN_TOKEN_NOT_CONFIGURED",
					"서버에 관리 토큰이 설정되지 않아 정책 변경을 사용할 수 없습니다.");
			return;
		}

		String provided = request.getHeader(HEADER);
		if (provided == null || provided.isBlank()) {
			reject(response, "ADMIN_TOKEN_REQUIRED", "관리 토큰이 필요합니다.");
			return;
		}

		// 상수 시간 비교 — 길이·내용에 따라 응답 시간이 달라지면 토큰을 한 바이트씩 추측할 수 있다.
		if (!MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedToken)) {
			reject(response, "ADMIN_TOKEN_INVALID", "관리 토큰이 올바르지 않습니다.");
			return;
		}

		filterChain.doFilter(request, response);
	}

	private void reject(HttpServletResponse response, String code, String message) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("""
				{"code":"%s","message":"%s"}""".formatted(code, message));
	}
}

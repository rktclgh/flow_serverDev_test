package flow.test.serverdev.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 파괴적인 API 를 관리 토큰으로 보호한다. (SPEC §7.0)
 *
 * <p>보호 대상은 둘이다 — {@code /api/extensions} 이하의 <b>쓰기</b> 요청과,
 * {@code /api/files} 의 <b>삭제</b>. 정책 조회·파일 업로드·목록·다운로드는 공개다.
 * 과제의 "누구나 접속 가능" 요구가 그쪽에 걸린다.
 *
 * <p>무인증으로 두면 누구나 {@code exe} 차단을 해제할 수 있어 정책 자체가 무의미해진다.
 * 삭제도 같다 — 공개면 누구나 목록을 훑어 남의 파일을 전부 지울 수 있다. 반면 계정 체계를
 * 도입하는 것은 과제 범위 밖이므로, 토큰 하나로 관리 기능만 분리했다.
 *
 * <p><b>경로 판정은 스프링의 매핑 엔진에 맡긴다</b>({@link #POLICY_PATHS} 주석 참고).
 * 인가하는 쪽과 컨트롤러를 고르는 쪽이 경로를 다르게 읽으면 그 차이가 그대로 우회가 된다.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

	private static final String HEADER = "X-Admin-Token";

	/**
	 * ★ 경로 판정을 <b>스프링의 매핑 엔진으로</b> 한다. 문자열 {@code startsWith} 가 아니다.
	 *
	 * <p>{@code getRequestURI()} 는 <b>디코딩되지 않은 날 것</b>이다. 반면 핸들러 매핑은
	 * 디코딩된 경로로 컨트롤러를 고른다. 두 시선이 어긋나면 그 틈이 그대로 인가 우회가 된다 —
	 * 실측했다({@code AdminTokenPathBypassTest}). 옛 구현에서 아래가 <b>토큰 없이</b> 통했다.
	 *
	 * <pre>
	 *   DELETE /api/%66iles/&#123;id&#125;       204, 파일이 실제로 삭제됐다
	 *   DELETE /%61pi/%66il%65s/&#123;id&#125;  204, 같은 결과
	 * </pre>
	 *
	 * <p>{@code "/api/%66iles/..."} 는 {@code startsWith("/api/files")} 를 통과하지 못해
	 * 필터가 <b>보호 대상이 아니라고 판단</b>하고, 그 뒤 매핑이 {@code %66} 을 {@code f} 로
	 * 디코딩해 컨트롤러로 보낸다. 변형을 하나씩 막는 방식으로는 닫을 수 없는 구멍이다 —
	 * 인코딩 변형은 얼마든지 만들 수 있다. <b>매핑과 같은 눈으로 보는 것</b>만이 답이다.
	 *
	 * <p>{@link PathPattern} 은 디스패처가 컨트롤러를 고를 때 쓰는 바로 그 엔진이고,
	 * {@link RequestPath} 는 세그먼트를 디코딩하고 매트릭스 파라미터를 떼어낸다.
	 * 그래서 <b>매핑이 받아들이는 모든 형태를 필터도 똑같이 본다.</b>
	 *
	 * <p>{@code /}{@code **} 는 <b>0개 이상</b>의 세그먼트를 받으므로 {@code /api/extensions}
	 * 자체도 보호된다. 덤으로 {@code /api/extensions-foo} 같은 이웃 경로를 잘못 삼키지 않는다 —
	 * 옛 {@code startsWith} 는 그것까지 보호 대상으로 여겼다.
	 */
	private static final PathPattern POLICY_PATHS = parse("/api/extensions/**");

	/**
	 * 파일 API. 여기서는 <b>메서드로 갈린다</b> — {@code DELETE} 만 보호하고
	 * 업로드({@code POST})·목록·다운로드({@code GET})는 공개로 둔다.
	 */
	private static final PathPattern FILE_PATHS = parse("/api/files/**");

	/**
	 * 활동 로그. <b>읽기인데도 보호한다</b> — 지금까지의 "읽기는 공개" 규칙에 대한 유일한 예외다.
	 *
	 * <p>이 경로는 관리 화면의 로그이고, 다른 사용자가 올린 <b>파일명과 차단 이력</b>이 담긴다.
	 * 업로드가 공개인 서비스에서 그 목록을 누구나 훑을 수 있으면, 무엇이 올라왔는지가 그대로
	 * 드러난다. 상태를 바꾸지 않는다는 것과 보여줘도 된다는 것은 다른 문제다.
	 */
	private static final PathPattern AUDIT_PATHS = parse("/api/audit/**");

	private static PathPattern parse(String pattern) {
		return PathPatternParser.defaultInstance.parse(pattern);
	}

	/**
	 * 최소 토큰 길이. SPEC §7.0 이 요구하는 값이며 <b>기동 시점에 강제한다</b>.
	 *
	 * <p>32자는 임의의 숫자가 아니다. 이 토큰은 만료도 잠금도 없고 요청마다 그대로 전송되므로
	 * 사실상 무제한 시도가 가능하다. 짧은 토큰은 그 조건에서 의미가 없다.
	 */
	private static final int MIN_TOKEN_LENGTH = 32;

	/** 상태를 바꾸지 않는 메서드. 정책 조회는 공개이므로 이들은 토큰을 요구하지 않는다. */
	private static final Set<String> SAFE_METHODS =
		Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

	private final byte[] expectedToken;
	private final boolean configured;

	public AdminTokenFilter(@Value("${app.admin-token:}") String adminToken) {
		this.configured = adminToken != null && !adminToken.isBlank();

		// 약한 토큰은 기동을 실패시킨다.
		//
		// 미설정을 fail-closed 로 막아놓고 짧은 토큰은 조용히 받아들이면 앞뒤가 맞지 않는다.
		// 오히려 후자가 더 위험하다 — 미설정은 아무도 못 바꾸지만, 약한 토큰은 누군가
		// 바꿀 수 있다는 뜻이고, 관리자는 보호되고 있다고 믿는다.
		//
		// 런타임 거부가 아니라 기동 실패인 이유: 설정 실수는 첫 요청이 아니라 배포 시점에
		// 드러나야 한다. 요청이 올 때까지 모른다면 이미 공개된 뒤다.
		if (configured && adminToken.length() < MIN_TOKEN_LENGTH) {
			throw new IllegalStateException(
				"app.admin-token 은 최소 %d자여야 합니다. 현재 %d자입니다. "
					.formatted(MIN_TOKEN_LENGTH, adminToken.length())
					+ "만료도 잠금도 없는 토큰이라 짧으면 방어가 되지 않습니다.");
		}

		this.expectedToken = configured ? adminToken.getBytes(StandardCharsets.UTF_8) : new byte[0];
	}

	/**
	 * <b>안전한 메서드</b>(RFC 9110 §9.2.1)는 통과시킨다. 상태를 바꾸지 않으므로 보호 대상이 아니다.
	 *
	 * <p>처음에는 GET 만 통과시켰는데, 그러면 HEAD 가 401 을 받는다. HEAD 는 본문 없는 GET 이라
	 * 헬스체크·모니터링·링크 검사기가 흔히 쓴다 — 읽기를 공개하기로 해놓고
	 * 읽기의 한 형태를 막는 셈이었다. OPTIONS 도 같은 이유로 통과시킨다.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// 디스패처가 컨트롤러를 고를 때와 같은 방식으로 경로를 읽는다.
		// getRequestURI() 를 그대로 쓰면 인코딩된 변형이 이 판정만 비켜 간다.
		PathContainer path = RequestPath
			.parse(request.getRequestURI(), request.getContextPath())
			.pathWithinApplication();

		// ★ 경로를 메서드보다 먼저 본다.
		//
		//   전에는 안전한 메서드를 먼저 통과시켰다. 그러면 활동 로그처럼 "읽기지만 보호해야
		//   하는" 경로를 아무리 패턴에 넣어도 GET 요청이 이 판정에 닿지도 못한다.
		//   보호 대상을 추가했는데 조용히 열려 있는 상태가 되는 것이 이 순서의 위험이다.
		if (AUDIT_PATHS.matches(path)) {
			return false;
		}

		if (SAFE_METHODS.contains(request.getMethod())) {
			return true;
		}

		if (POLICY_PATHS.matches(path)) {
			return false;
		}
		// 파일은 삭제만 보호한다. POST(업로드)는 공개를 유지해야 한다.
		return !(FILE_PATHS.matches(path) && HttpMethod.DELETE.matches(request.getMethod()));
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

	/**
	 * 필터가 만드는 응답도 컨트롤러 응답과 <b>같은 형태</b>여야 한다.
	 *
	 * <p>{@code setCharacterEncoding} 을 쓰면 헤더가 {@code application/json;charset=UTF-8} 이 되어
	 * 스프링이 내보내는 {@code application/json} 과 달라진다. JSON 은 규격상 UTF-8 이라
	 * 파라미터가 필요 없으므로, 바이트를 직접 써서 두 경로의 헤더를 일치시킨다.
	 */
	private void reject(HttpServletResponse response, String code, String message) throws IOException {
		byte[] body = """
				{"code":"%s","message":"%s"}""".formatted(code, message)
			.getBytes(StandardCharsets.UTF_8);

		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
	}
}

package flow.test.serverdev.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Spring Security 를 쓰지만 목적이 인증은 아니다.
 *
 * <p>인가는 {@link AdminTokenFilter} 가 담당하고, 여기서는 두 가지만 맡는다.
 *
 * <ol>
 *   <li><b>보안 헤더</b> — nginx 에만 두면 {@code docker compose up} 으로 띄운 환경(제출물을
 *       clone 해서 실행하는 평가자)에는 헤더가 하나도 붙지 않는다. 인프라에 의존한 방어는
 *       배포 형태가 바뀌는 순간 증발하므로 앱 레벨에 고정한다.
 *   <li><b>STATELESS</b> — 세션 쿠키를 아예 만들지 않는다. CSRF 표면 자체가 사라진다.
 * </ol>
 *
 * <p><b>CORS 를 설정하지 않는 이유</b>: React 빌드 산출물을 Spring 의 static 리소스로 번들하므로
 * 페이지와 API 의 출처가 동일하다. 동일 출처 요청에는 CORS 검사가 발동하지 않는다.
 * CORS 는 서버 접근을 통제하는 장치가 아니라 브라우저의 cross-origin 응답 읽기를 허용하는
 * <i>완화</i> 장치이므로, 열지 않는 편이 표면이 좁다.
 *
 * <p><b>CSRF 를 비활성화하는 이유</b>: 쿠키·세션을 쓰지 않고 {@code X-Admin-Token} 커스텀 헤더로
 * 인증한다. 커스텀 헤더는 cross-origin 요청에 임의로 붙일 수 없고(preflight 가 필요하다),
 * 위와 같이 CORS 를 열지 않았으므로 그 preflight 가 통과하지 못한다.
 * 즉 "CORS 를 열지 않은 것"이 그대로 CSRF 방어가 된다. 쿠키 인증이었다면 CSRF 토큰이 필수다.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.cors(cors -> cors.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			// 접근 제어는 AdminTokenFilter 가 수행한다.
			// 여기서 permitAll 을 두지 않으면 Security 기본값이 모든 요청에 로그인을 요구한다.
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.headers(headers -> headers
				// 브라우저 MIME 스니핑 차단. 업로드 파일을 다루는 서비스에서는 필수다.
				.contentTypeOptions(contentType -> {})
				.frameOptions(frame -> frame.deny())
				.httpStrictTransportSecurity(hsts -> hsts
					.includeSubDomains(true)
					.maxAgeInSeconds(31_536_000))
				.referrerPolicy(referrer -> referrer
					.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
			);

		return http.build();
	}
}

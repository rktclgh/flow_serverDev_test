package flow.test.serverdev.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 속도 제한 필터 배선. (SPEC §10.4, §21.9)
 *
 * <p><b>등록 방식을 하나로 고정한다.</b> {@code @Component} 자동 등록과
 * {@code http.addFilterBefore(...)} 를 함께 쓰면 같은 요청에서 필터가 두 번 돌아
 * <b>토큰이 두 번 차감</b>된다. 그러면 설정한 burst 의 절반에서 429 가 나가는데, 로그에는
 * 정상적인 거부로 보이므로 아무도 이유를 모른다.
 *
 * <p>그래서 여기 한 곳에서만 등록한다. {@link RateLimitFilter} 는 빈이지만 이 등록 빈이
 * 참조하고 있어 서블릿 자동 등록 대상에서 빠진다 — Boot 의 {@code ServletContextInitializerBeans}
 * 가 등록 빈이 이미 참조하는 필터를 중복 등록하지 않는다. 빈으로 두는 이유는 테스트가
 * 필터 자체를 관측할 수 있어야 하기 때문이다.
 *
 * <p>{@code /api/files} 에만 건다. 다운로드({@code /api/files/&#123;id&#125;/content})와 정책 API 는
 * 대상이 아니다 — 정확 경로 패턴이라 하위 경로에는 걸리지 않는다.
 *
 * <p>순서를 가장 앞으로 두는 이유는 <b>거부할 요청에 비용을 쓰지 않기 위해서</b>다.
 * multipart 파싱은 어차피 서블릿(DispatcherServlet) 안이라 모든 필터보다 뒤지만,
 * 보안 필터 체인·헤더 기록기까지 앞질러야 실제로 가장 싸다.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

	@Bean
	@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true",
			matchIfMissing = true)
	public RateLimitFilter rateLimitFilter(RateLimitProperties properties) {
		return new RateLimitFilter(properties, System::nanoTime);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true",
			matchIfMissing = true)
	public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
		FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setName("rateLimitFilter");
		registration.addUrlPatterns(RateLimitFilter.UPLOAD_PATH);
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}
}

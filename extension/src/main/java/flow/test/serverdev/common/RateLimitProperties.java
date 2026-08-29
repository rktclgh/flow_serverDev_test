package flow.test.serverdev.common;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * 업로드 속도 제한 설정. (SPEC §10.4, §21.9)
 *
 * <p>기본값은 <b>IP 당 60r/m(초당 1개), burst 10</b> 이다. nginx(300r/m)보다 앱이 더 조이는데,
 * 방향이 뒤바뀐 것이 아니라 역할이 다르다 — <b>사용자에게 이유를 설명해야 하는 거부는 앱이
 * 해야 한다.</b> nginx 는 HTML 을 돌려주고 앱 로그에도 아무것도 남기지 않으므로 명백한 남용만
 * 끊는다. 크기 제한에서 앱(10MB)이 nginx(12MB)보다 조인 것과 같은 이유이고 같은 방향이다.
 *
 * <p>{@code maxEntries} 는 <b>속도 제한 자체가 메모리 고갈 표면이 되는 것</b>을 막는다.
 * 키가 클라이언트 주소라 상한 없이 쌓으면 요청만으로 힙을 채울 수 있다. 상한은 LRU 가 아니라
 * <b>세대 교체</b>로 지킨다 — 근거는 {@link RateLimitFilter} 주석에 있다.
 *
 * @param enabled            꺼두면 필터 자체를 등록하지 않는다
 * @param perMinute          IP 당 분당 보충량. 지속 처리율이다
 * @param burst              버킷 용량. 순간적으로 몰아 쓸 수 있는 양이다
 * @param generationInterval 세대 교체 주기. 이 시간 동안 조용한 버킷은 버려진다
 * @param maxEntries         한 세대가 담는 최대 항목 수. 이 수를 넘으면 시간과 무관하게 교체한다
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("60") @Positive int perMinute,
		@DefaultValue("10") @Positive int burst,
		@DefaultValue("10m") @DurationMin(nanos = 1) Duration generationInterval,
		@DefaultValue("10000") @Positive int maxEntries) {
}

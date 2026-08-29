package flow.test.serverdev.policy.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 고정 확장자 토글 요청. (SPEC §7.2)
 *
 * <p>{@code Boolean} 래퍼인 것은 의도다. {@code boolean} 이면 필드가 빠졌을 때
 * 조용히 {@code false} 가 되어 <b>"체크 해제"로 처리된다</b>.
 * 누락과 명시적 false 는 다른 요청이므로 구분해야 한다.
 */
public record FixedToggleRequest(@NotNull Boolean blocked) {}

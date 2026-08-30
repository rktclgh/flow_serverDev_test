package flow.test.serverdev.policy.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 커스텀 확장자 추가 요청. (SPEC §7.3)
 *
 * <p><b>{@code @NotBlank} 를 쓰지 않는다.</b> 빈 문자열의 판정은 정규화기가 이미 한다
 * ({@code RejectReason.EMPTY}). 여기서도 막으면 같은 입력이 요청 크기에 따라
 * {@code REQUEST_INVALID} 가 되었다 {@code EXT_INVALID_FORMAT} 이 되었다 한다.
 * 값에 대한 판정은 한 곳에서만 한다.
 *
 * <p>{@code @NotNull} 만 두는 이유는 필드 누락이 <b>값의 문제가 아니라 본문 구조의 문제</b>라서다.
 */
public record CustomCreateRequest(@NotNull String name) {}

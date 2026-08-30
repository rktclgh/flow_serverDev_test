package flow.test.serverdev.policy.dto;

import java.util.List;

/**
 * 정책 조회 응답. (SPEC §7.1)
 *
 * <p>{@code customCount} 와 {@code customLimit} 을 함께 내려 화면이 "12 / 200" 을
 * 서버에 다시 묻지 않고 그릴 수 있게 한다. 상한을 프론트에 상수로 박아두면
 * 서버가 상한을 바꿨을 때 화면만 거짓말을 하게 된다.
 */
public record PolicyResponse(
		List<FixedItem> fixed,
		List<CustomItem> custom,
		int customCount,
		int customLimit) {

	/** 고정 확장자. 체크 상태를 함께 내린다. */
	public record FixedItem(String name, boolean blocked) {}

	/** 커스텀 확장자. 존재 자체가 차단이므로 상태 필드가 없다. */
	public record CustomItem(String name) {}
}

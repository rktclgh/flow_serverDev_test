package flow.test.serverdev.upload;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 여러 건을 한 번에 지우는 요청. (DELETE /api/files)
 *
 * <p><b>상한을 두는 이유는 요청 하나가 일으키는 일의 양을 묶기 위해서다.</b> 상한이 없으면
 * 요청 한 번이 임의로 많은 객체 삭제를 일으킬 수 있다. 화면에서 고를 수 있는 수를 크게
 * 웃도는 값이라 정상 사용에는 걸리지 않는다.
 *
 * <p>원소 타입을 {@code String} 이 아니라 {@link UUID} 로 둔다. 형식이 어긋난 값은
 * 역직렬화에서 걸러져 서비스까지 내려오지 않는다 — 검증을 한 곳에서 끝낸다.
 */
public record BulkDeleteRequest(
		@NotEmpty(message = "지울 파일을 하나 이상 지정해야 합니다.")
		@Size(max = BulkDeleteRequest.MAX_IDS, message = "한 번에 " + BulkDeleteRequest.MAX_IDS + "건까지 지울 수 있습니다.")
		List<UUID> fileIds) {

	public static final int MAX_IDS = 100;
}

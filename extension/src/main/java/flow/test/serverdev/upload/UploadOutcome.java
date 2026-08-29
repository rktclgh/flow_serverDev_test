package flow.test.serverdev.upload;

import java.util.Map;
import java.util.UUID;

import flow.test.serverdev.common.ErrorCode;

/**
 * 업로드 처리 결과. (SPEC §21.6)
 *
 * <p>거부는 <b>값</b>이고 스토리지·감사 실패는 <b>예외</b>다. 기준은 "호출자가 무시해도 되는가" 다.
 * 차단 확장자 거부는 정상 판정이라 호출자가 응답으로 바꾸면 되지만, 저장소가 죽은 것은
 * 무시하면 안 된다.
 */
public sealed interface UploadOutcome {

	/** @param fileId 클라이언트에 노출되는 식별자. 순차 id 가 아니라 열거할 수 없다 */
	record Stored(UUID fileId, String originalFilename, long size) implements UploadOutcome {
	}

	record Rejected(ErrorCode code, Map<String, Object> detail) implements UploadOutcome {
	}
}

package flow.test.serverdev.upload;

import java.util.UUID;

/**
 * 업로드 성공 응답. (SPEC §7.5)
 *
 * @param fileId           UUID 다. 순차 id 를 노출하지 않으므로 열거로 남의 파일을 찾을 수 없다
 * @param originalFilename <b>이스케이프된</b> 값이다. 제어문자·양방향 제어가 그대로 화면과
 *                         헤더로 나가면 그것이 파일명 스푸핑이다
 * @param size             바이트 수
 */
public record UploadResponse(UUID fileId, String originalFilename, long size) {

	static UploadResponse from(UploadOutcome.Stored stored) {
		return new UploadResponse(stored.fileId(), stored.originalFilename(), stored.size());
	}
}

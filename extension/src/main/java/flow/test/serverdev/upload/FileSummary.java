package flow.test.serverdev.upload;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 목록에 실리는 파일 하나. (GET /api/files)
 *
 * <p><b>감사 기록에서 내보낼 수 있는 것만 담는다.</b> 같은 행에는 {@code client_ip}·
 * {@code reason_code}·{@code stored_key} 도 있지만 그것은 관리자의 정보다. 목록은 공개
 * 엔드포인트이므로, 무엇을 넣지 <b>않았는가</b>가 이 record 의 설계다.
 *
 * @param fileId           클라이언트가 지목하는 식별자. 다운로드·삭제가 이 값을 쓴다
 * @param originalFilename <b>이스케이프된</b> 값이다. 기록에 남은 그대로 내보낸다 —
 *                         되돌리면 RTL 재정의 같은 문자가 화면으로 나간다(SPEC §21.8)
 * @param size             바이트 수
 * @param uploadedAt       업로드 시각({@code occurred_at})
 */
public record FileSummary(UUID fileId, String originalFilename, Long size, OffsetDateTime uploadedAt) {
}

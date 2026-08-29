package flow.test.serverdev.upload;

import java.util.List;

/**
 * 목록 응답. (GET /api/files)
 *
 * <p>배열을 최상위로 내보내지 않고 객체로 감싼다. 나중에 총 개수나 상한 도달 여부를 더할 때
 * 최상위 타입을 바꾸면 기존 클라이언트가 전부 깨지지만, 필드를 더하는 것은 깨지 않는다.
 */
public record FileListResponse(List<FileSummary> files) {
}

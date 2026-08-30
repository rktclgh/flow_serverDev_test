package flow.test.serverdev.upload;

import java.util.List;
import java.util.UUID;

/**
 * 여러 건 삭제의 <b>건별</b> 결과.
 *
 * <p>전부 성공 아니면 전부 실패로 답하지 않는 이유는 <b>목록이 낡을 수 있기 때문</b>이다.
 * 다른 탭에서 이미 지웠거나 스위퍼가 걷어간 뒤일 수 있는데, 그 한 건 때문에 요청 전체를
 * 거부하면 사용자는 새로고침하고 다시 고르는 일을 반복하게 된다.
 *
 * <p>{@code notFound} 에는 <b>지울 수 없었던 모든 경우</b>가 들어간다 — 애초에 없던 id,
 * 이미 지운 파일, 차단돼서 저장된 적이 없는 기록이 모두 여기다. 셋을 나눠 답하면
 * <b>응답 차이만으로 "그 식별자는 존재하지만 차단됐다" 를 알아낼 수 있다</b>.
 * 감사 기록의 내용은 관리자의 것이지 요청자의 것이 아니다({@code FILE_NOT_FOUND} 와 같은 판단이다).
 */
public record BulkDeleteResponse(List<UUID> deleted, List<UUID> notFound) {
}

package flow.test.serverdev.audit;

import java.time.OffsetDateTime;

/**
 * 활동 로그 한 줄. 정책 변경과 업로드 판정을 <b>같은 모양</b>으로 담는다.
 *
 * <p>두 기록은 컬럼이 다르지만 화면에서는 한 줄기로 섞여 흐른다. 화면이 종류별로 분기해
 * 다른 필드를 읽게 하면 표시 규칙이 둘이 되므로, 서버가 공통 형태로 접어서 내려준다.
 *
 * <p><b>요청자 주소를 함께 담는다.</b> 감사가 답해야 하는 것은 "무엇이 왜" 만이 아니라
 * <b>"누가"</b> 이기도 하다. 지금은 관리 토큰이 하나뿐이라 계정으로는 관리자를 구분할 수 없고,
 * 구분에 쓸 수 있는 유일한 신호가 주소다. 인증이 들어와 관리자가 여럿이 되면 그때 계정을
 * 남기고 주소는 보조 신호로 물러난다 — 지금 빼두면 그 사이의 변경은 누가 했는지 영원히 알 수 없다.
 *
 * @param kind     POLICY 또는 UPLOAD
 * @param action   정책이면 변경 종류, 업로드면 판정 결과
 * @param target   정책이면 확장자, 업로드면 원본 파일명
 * @param detail   사람이 읽을 부연. 차단 사유처럼 "왜" 에 해당하는 값이 여기 들어간다
 * @param clientIp 요청자 주소. 얻지 못한 경우 null
 */
public record AuditEntry(
		OffsetDateTime at,
		String kind,
		String action,
		String target,
		String detail,
		String clientIp) {
}

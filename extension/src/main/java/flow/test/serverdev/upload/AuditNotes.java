package flow.test.serverdev.upload;

/**
 * 감사 기록의 {@code note} 에 남기는 <b>관측 신호</b>.
 *
 * <p>이 컬럼은 값이 아니라 <b>신호 이름</b>을 담는다. 스키마가 40자로 잡은 것도 그 전제였다
 * (V2 마이그레이션 주석의 예시가 이 상수다). 이름만 넣으면 길이가 입력에 따라 변하지 않아
 * <b>구조적으로</b> 상한 안에 들어온다 — 자르지 않아도 되고, 그래서 감사 값이 조용히
 * 잘려 나가는 일이 없다.
 *
 * <p>세부는 {@code original_filename} 에 이미 있다. 신호는 "무엇을 봤는가" 를 답하고,
 * 그 답을 조건으로 세면 위장 시도가 몇 건인지 집계할 수 있다.
 */
final class AuditNotes {

	/** 차단 목록에 있는 확장자가 <b>마지막이 아닌 자리</b>에 나타났다. 차단하지는 않았다. */
	static final String SUSPICIOUS_MIDDLE_SEGMENT = "SUSPICIOUS_MIDDLE_SEGMENT";

	private AuditNotes() {
	}
}

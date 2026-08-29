package flow.test.serverdev.upload;

/**
 * 파일명 자체가 부적합해 분석을 진행할 수 없는 사유. (SPEC §5)
 *
 * <p>확장자 정책과 무관하게 파일명 단계에서 걸러진다.
 * 사유를 세분화하는 이유는 감사 로그({@code upload_audit.reason_code})에
 * "무엇이 이상했는지"를 남기기 위함이다. HTTP 응답에서는 EMPTY/NULL_BYTE/BIDI_CONTROL 이
 * 모두 FILE_NAME_INVALID 로 합쳐진다 — 공격자에게 탐지 세부를 알려줄 이유가 없다.
 */
public enum FilenameRejectReason {

	/** null 이거나 공백뿐인 파일명 */
	EMPTY,

	/**
	 * 널바이트(U+0000) 포함.
	 * 절단하지 않고 거부한다 — 절단은 {@code safe.jpg\0.exe} 를 공격자 의도대로 처리하는 것이다.
	 */
	NULL_BYTE,

	/**
	 * 양방향 제어문자 포함.
	 * {@code photo}+U+202E+{@code gnp.exe} 는 화면에 {@code photoexe.png} 로 보인다.
	 */
	BIDI_CONTROL,

	/** 정규화 후 255 코드포인트 초과 */
	TOO_LONG
}

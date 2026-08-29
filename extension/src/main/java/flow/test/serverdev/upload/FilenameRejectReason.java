package flow.test.serverdev.upload;

/**
 * 파일명 자체가 부적합해 분석을 진행할 수 없는 사유. (SPEC §5)
 *
 * <p>사유를 세분화하는 이유는 감사 로그({@code upload_audit.reason_code})에
 * "무엇이 이상했는지"를 남기기 위함이다. HTTP 응답에서는 이들이 모두
 * {@code FILE_NAME_INVALID} 로 합쳐진다 — 공격자에게 탐지 세부를 알려줄 이유가 없다.
 */
public enum FilenameRejectReason {

	/** null 이거나 공백뿐인 파일명 */
	EMPTY,

	/**
	 * 널바이트(U+0000) 포함.
	 * 절단하지 않고 거부한다 — 절단은 {@code safe.jpg}+NUL+{@code .exe} 를
	 * 공격자 의도대로 {@code safe.jpg} 로 처리하는 것이다.
	 */
	NULL_BYTE,

	/**
	 * 양방향 제어문자 포함.
	 * {@code photo}+U+202E+{@code gnp.exe} 는 화면에 {@code photoexe.png} 로 보인다.
	 * 시각적 위장이라는 공격 성격이 뚜렷해 다른 제어문자와 구분해 기록한다.
	 */
	BIDI_CONTROL,

	/**
	 * 그 밖의 제어문자(Cc)·서식문자(Cf) 포함.
	 *
	 * <p>두 종류의 공격을 함께 막는다.
	 * <ul>
	 *   <li>CR/LF — 원본 파일명이 {@code Content-Disposition} 헤더나 로그로 흘러갈 때의 주입 경계
	 *   <li>ZWSP(U+200B)·BOM(U+FEFF) 등 보이지 않는 문자 — {@code invoice.exe}+ZWSP 처럼
	 *       확장자 뒤에 붙여 정규화를 실패시키고 "확장자 없음" 으로 빠져나가는 우회
	 * </ul>
	 *
	 * <p>공백류(Zs)는 여기 해당하지 않는다. {@code archive.tar.gz backup} 같은
	 * 사람이 붙인 정상 파일명을 거부하지 않기 위함이다.
	 */
	CONTROL_CHARACTER,

	/** 원시 입력 또는 정규화된 basename 이 길이 상한을 초과 */
	TOO_LONG
}

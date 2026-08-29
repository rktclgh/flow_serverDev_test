package flow.test.serverdev.audit;

/**
 * 원본 파일명을 기록에 안전한 형태로 바꾼다.
 *
 * <p><b>거부된 요청도 기록한다</b>는 점이 핵심이다. 거부 사유가 바로 그 파일명의
 * 제어문자인 경우가 대부분이므로, 기록 대상에는 널바이트·CR/LF·RTL 재정의 같은 것이
 * 그대로 들어온다. 그것을 손대지 않고 저장하면 이 값을 읽는 모든 곳이 위험해진다.
 *
 * <ul>
 *   <li>CR/LF — 로그 한 줄을 두 줄로 쪼개 <b>가짜 로그 항목</b>을 만든다
 *   <li>RTL 재정의 — 관리 화면에서 파일명이 <b>다른 이름으로 보인다</b>.
 *       공격자가 무엇을 올렸는지 조사하는 사람이 잘못 읽게 된다
 *   <li>널바이트·ZWSP — 눈에 보이지 않아 "왜 거부됐는지" 를 아무도 설명하지 못한다
 * </ul>
 *
 * <p>제거하지 않고 {@code \\uXXXX} 로 <b>보이게</b> 만든다. 제거하면 무엇이 왔는지
 * 알 수 없게 되어 기록의 목적이 사라진다.
 */
public final class AuditFilenames {

	/**
	 * 이스케이프 후 길이 상한.
	 *
	 * <p>파일명은 최대 4096자까지 들어올 수 있고(FilenameAnalyzer 의 원시 상한),
	 * 제어문자로 가득 차 있으면 6배로 부풀어 24KB 가 된다. 공격자가 요청 하나로
	 * 감사 테이블에 24KB 를 쓰게 만들 수 있다는 뜻이다. 증거로서의 가치는 앞부분에 있다.
	 */
	static final int MAX_LENGTH = 1024;

	private static final String TRUNCATED = "…(truncated)";

	private AuditFilenames() {
	}

	public static String forRecord(String rawFilename) {
		if (rawFilename == null) {
			return "";
		}

		StringBuilder escaped = new StringBuilder(rawFilename.length());
		rawFilename.codePoints().forEach(codePoint -> {
			int type = Character.getType(codePoint);
			if (type == Character.CONTROL || type == Character.FORMAT) {
				escaped.append("\\u%04x".formatted(codePoint));
			}
			else {
				escaped.appendCodePoint(codePoint);
			}
		});

		if (escaped.length() <= MAX_LENGTH) {
			return escaped.toString();
		}
		return escaped.substring(0, MAX_LENGTH - TRUNCATED.length()) + TRUNCATED;
	}
}

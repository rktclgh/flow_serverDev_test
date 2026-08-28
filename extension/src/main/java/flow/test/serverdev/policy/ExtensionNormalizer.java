package flow.test.serverdev.policy;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 확장자 문자열 정규화의 <b>단일 진입점</b>. (SPEC §4)
 *
 * <p>정책 저장 경로와 업로드 검증 경로가 반드시 이 클래스를 통과해야 한다.
 * 이 과제의 모든 우회 공격은 "정책에 저장된 문자열"과 "파일명에서 추출한 문자열"이
 * 서로 다른 규칙으로 만들어질 때 생기는 틈에서 나온다.
 *
 * <p><b>단계 순서가 곧 명세다.</b> 두 지점은 순서를 바꾸면 즉시 뚫린다.
 *
 * <ul>
 *   <li>NFKC 를 strip 보다 먼저 — {@code String.strip()} 은 {@code Character.isWhitespace}
 *       기반이라 NBSP(U+00A0)를 제거하지 못한다. NFKC 가 먼저 U+0020 으로 접어야 제거된다.
 *   <li>길이 검사를 패턴 검사보다 먼저 — 나중에 하면 21자 입력이 "허용되지 않는 문자"로
 *       뭉개져 사용자에게 잘못된 안내가 나간다.
 * </ul>
 */
@Component
public class ExtensionNormalizer {

	/** SPEC §3.1 의 ck_blocked_extension_format CHECK 제약과 동일한 규칙. */
	private static final Pattern ALLOWED = Pattern.compile("^[a-z0-9]{1,20}$");

	private static final int MAX_LENGTH = 20;

	public NormalizeResult normalize(String raw) {
		// 1. null / 공백만 있는 값
		if (raw == null || raw.isBlank()) {
			return new NormalizeResult.Rejected(RejectReason.EMPTY);
		}

		// 2. 유니코드 정규화. 전각 문자 -> ASCII, NBSP -> 일반 공백.
		//    NFC 가 아니라 NFKC 여야 호환 문자가 접힌다.
		String value = Normalizer.normalize(raw, Normalizer.Form.NFKC);

		// 3. 앞뒤 공백 제거. 2번이 선행해야 NBSP 계열이 여기서 걸린다.
		value = value.strip();

		// 4. 선행 점은 하나만 제거한다. ".sh" -> "sh"
		//    "..sh" 는 점이 남아 8번 패턴 검사에서 거부된다 — 의도된 동작이다.
		if (value.startsWith(".")) {
			value = value.substring(1);
		}

		// 5. 후행 점/공백 제거. Windows 는 "exe." 과 "exe " 를 "exe" 로 해석하므로
		//    이를 남겨두면 확장자 검사를 우회할 수 있다.
		//    "exe ." 처럼 섞인 경우가 있어 더 제거할 것이 없을 때까지 반복한다.
		value = stripTrailingDotsAndSpaces(value);

		// 6. Locale.ROOT 고정. 지정하지 않으면 터키어 로케일에서 I -> U+0131 이 되어
		//    고정 확장자 js 가 조용히 뚫린다.
		value = value.toLowerCase(Locale.ROOT);

		// 정규화 과정에서 내용이 모두 사라진 경우 (".", "..", NBSP 단독 등)
		if (value.isEmpty()) {
			return new NormalizeResult.Rejected(RejectReason.EMPTY);
		}

		// 7. 길이 초과를 형식 오류보다 먼저 판정해 안내를 구분한다.
		if (value.length() > MAX_LENGTH) {
			return new NormalizeResult.Rejected(RejectReason.TOO_LONG);
		}

		// 8. 최종 화이트리스트
		if (!ALLOWED.matcher(value).matches()) {
			return new NormalizeResult.Rejected(RejectReason.INVALID_CHARACTER);
		}

		return new NormalizeResult.Ok(value);
	}

	private static String stripTrailingDotsAndSpaces(String value) {
		int end = value.length();
		while (end > 0) {
			char last = value.charAt(end - 1);
			if (last == '.' || Character.isWhitespace(last)) {
				end--;
			} else {
				break;
			}
		}
		return value.substring(0, end);
	}
}

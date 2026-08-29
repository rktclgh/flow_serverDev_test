package flow.test.serverdev.upload;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import flow.test.serverdev.policy.ExtensionNormalizer;
import flow.test.serverdev.policy.NormalizeResult;

/**
 * 업로드 파일명에서 차단 판정 대상 확장자를 추출한다. (SPEC §5)
 *
 * <p>확장자 정규화는 {@link ExtensionNormalizer} 에 위임한다.
 * 정책 저장 경로와 업로드 검증 경로가 물리적으로 같은 코드를 쓰게 하기 위함이다.
 *
 * <p><b>차단 판정은 마지막 확장자만으로 한다.</b> Windows 확장자 숨김은 마지막 확장자만
 * 숨기므로 위험한 쪽은 {@code file.txt.exe} 이고, 그것은 마지막만 봐도 잡힌다.
 * 중간 확장자가 실제 위협이 되는 경우({@code shell.php.jpg} 가 PHP 로 실행)는
 * 웹서버가 다중 확장자를 핸들러로 매칭할 때인데, 저장 키가 UUID 라 그 경로가 존재하지 않는다.
 * 중간 세그먼트는 수집하되 <b>차단하지 않고 감사 로그에만</b> 남긴다.
 */
@Component
public class FilenameAnalyzer {

	/** 대부분 파일시스템의 NAME_MAX. Content-Disposition 헤더 길이 폭증도 함께 막는다. */
	private static final int MAX_FILENAME_LENGTH = 255;

	/**
	 * 양방향 제어문자. 화면 표시와 실제 바이트가 달라지므로 파일명에 허용하지 않는다.
	 * LRM/RLM, LRE/RLE/PDF/LRO/RLO, LRI/RLI/FSI/PDI.
	 */
	private static final Set<Integer> BIDI_CONTROLS = Set.of(
		0x200E, 0x200F,
		0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
		0x2066, 0x2067, 0x2068, 0x2069);

	private final ExtensionNormalizer extensionNormalizer;

	public FilenameAnalyzer(ExtensionNormalizer extensionNormalizer) {
		this.extensionNormalizer = extensionNormalizer;
	}

	public FilenameAnalysis analyze(String rawFilename) {
		// 1. null / 공백뿐인 파일명
		if (rawFilename == null || rawFilename.isBlank()) {
			return reject(FilenameRejectReason.EMPTY);
		}

		// 2. 유니코드 정규화. 전각 마침표(U+FF0E)가 여기서 ASCII 점이 되어 7번 처리 대상이 된다.
		String name = Normalizer.normalize(rawFilename, Normalizer.Form.NFKC);

		// NBSP 만 있는 파일명은 1번을 통과한다(isWhitespace 가 false). NFKC 후 다시 확인한다.
		if (name.isBlank()) {
			return reject(FilenameRejectReason.EMPTY);
		}

		// 3. 널바이트는 절단하지 않고 거부한다.
		//    "safe.jpg\0.exe" 를 절단하면 공격자 의도대로 safe.jpg 로 처리하는 셈이 된다.
		if (name.indexOf(0) >= 0) {
			return reject(FilenameRejectReason.NULL_BYTE);
		}

		// 4. 양방향 제어문자. photo + U+202E + "gnp.exe" 는 화면에 photoexe.png 로 보인다.
		if (name.codePoints().anyMatch(BIDI_CONTROLS::contains)) {
			return reject(FilenameRejectReason.BIDI_CONTROL);
		}

		// 5. 길이. 코드포인트 기준 — NFKC 가 길이를 늘릴 수 있으므로 정규화 이후에 센다.
		if (name.codePointCount(0, name.length()) > MAX_FILENAME_LENGTH) {
			return reject(FilenameRejectReason.TOO_LONG);
		}

		// 6. 경로 구분자 제거. 브라우저는 basename 만 보내지만 curl 은 임의 값을 보낼 수 있다.
		String base = basename(name);

		// 7. ★ basename 전체의 후행 점/공백을 확장자 분리보다 <b>먼저</b> 제거한다.
		//    이 순서가 아니면 "script.sh." 의 마지막 점 뒤가 빈 문자열이 되어
		//    "확장자 없음" 으로 빠져나간다. Windows 는 이를 "script.sh" 로 해석한다.
		base = stripTrailingDotsAndSpaces(base);

		if (base.isEmpty()) {
			return reject(FilenameRejectReason.EMPTY);
		}

		// 8~9. 마지막 확장자와 중간 세그먼트 분리
		return new FilenameAnalysis.Ok(base, lastExtensionOf(base), middleSegmentsOf(base));
	}

	private static String basename(String name) {
		int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		return separator >= 0 ? name.substring(separator + 1) : name;
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

	/** 마지막 점 뒤를 정규화한다. 정규화에 실패하면 확장자가 아닌 것으로 본다. */
	private Optional<String> lastExtensionOf(String base) {
		int dot = base.lastIndexOf('.');
		if (dot < 0) {
			return Optional.empty();
		}
		return normalized(base.substring(dot + 1));
	}

	/**
	 * 첫 조각(파일명 본체)과 마지막 조각(확장자)을 뺀 가운데 조각들.
	 * 차단 판정에는 쓰지 않고 감사 로그에만 기록한다.
	 */
	private List<String> middleSegmentsOf(String base) {
		String[] parts = base.split("\\.", -1);
		List<String> middle = new ArrayList<>();
		for (int i = 1; i < parts.length - 1; i++) {
			normalized(parts[i]).ifPresent(middle::add);
		}
		return List.copyOf(middle);
	}

	private Optional<String> normalized(String candidate) {
		return extensionNormalizer.normalize(candidate) instanceof NormalizeResult.Ok ok
			? Optional.of(ok.value())
			: Optional.empty();
	}

	private static FilenameAnalysis reject(FilenameRejectReason reason) {
		return new FilenameAnalysis.Rejected(reason);
	}
}

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
 * {@code invoice.exe.pdf} 는 실제로 PDF 이며 PDF 뷰어로 열린다 — 차단하면 오탐이다.
 * 중간 세그먼트는 수집하되 차단하지 않고 감사 로그에만 남긴다.
 */
@Component
public class FilenameAnalyzer {

	/**
	 * 정규화 이전 원시 입력 상한. 파일명은 공격자가 통제하는 입력이므로
	 * NFKC 할당·코드포인트 순회 같은 비용을 치르기 <b>전에</b> 먼저 끊는다.
	 * 정상 파일명은 255 이하이며, multipart 헤더 현실을 감안해 넉넉히 잡았다.
	 */
	private static final int MAX_RAW_LENGTH = 4096;

	/** 대부분 파일시스템의 NAME_MAX. Content-Disposition 헤더 길이 폭증도 함께 막는다. */
	private static final int MAX_BASENAME_LENGTH = 255;

	/**
	 * 양방향 제어문자. 모두 Cf 이지만 "시각적 위장" 이라는 공격 성격이 뚜렷해
	 * 다른 제어문자와 구분해 기록한다. ALM(U+061C)을 포함한다.
	 */
	private static final Set<Integer> BIDI_CONTROLS = Set.of(
		0x061C, 0x200E, 0x200F,
		0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
		0x2066, 0x2067, 0x2068, 0x2069);

	private final ExtensionNormalizer extensionNormalizer;

	public FilenameAnalyzer(ExtensionNormalizer extensionNormalizer) {
		this.extensionNormalizer = extensionNormalizer;
	}

	public FilenameAnalysis analyze(String rawFilename) {
		// 1. null
		if (rawFilename == null) {
			return reject(FilenameRejectReason.EMPTY);
		}

		// 2. 원시 상한을 가장 먼저 적용한다.
		//    length() 는 O(1) 이지만 isBlank() 는 O(n) 이라 거대한 공백 문자열이 오면
		//    상한 검사에 닿기도 전에 전체를 순회한다. 상한의 목적이 비용 상한이므로
		//    그 앞에 O(n) 연산을 두면 목적이 훼손된다.
		if (rawFilename.length() > MAX_RAW_LENGTH) {
			return reject(FilenameRejectReason.TOO_LONG);
		}

		// 3. 공백뿐인 파일명
		if (rawFilename.isBlank()) {
			return reject(FilenameRejectReason.EMPTY);
		}

		// 3. 유니코드 정규화. 전각 마침표(U+FF0E)가 여기서 ASCII 점이 되어 후행 정리 대상이 된다.
		String name = Normalizer.normalize(rawFilename, Normalizer.Form.NFKC);

		// NBSP 만 있는 파일명은 1번을 통과한다(isWhitespace 가 false). NFKC 후 다시 확인한다.
		if (name.isBlank()) {
			return reject(FilenameRejectReason.EMPTY);
		}

		// 4. 널바이트는 절단하지 않고 거부한다.
		//    "safe.jpg"+NUL+".exe" 를 절단하면 공격자 의도대로 safe.jpg 로 처리하는 셈이다.
		if (name.indexOf(0) >= 0) {
			return reject(FilenameRejectReason.NULL_BYTE);
		}

		// 5. 제어문자(Cc)와 서식문자(Cf)를 거부한다.
		//    개별 코드포인트를 나열하지 않고 유니코드 카테고리로 판정하는 이유는
		//    ALM(U+061C)·BOM(U+FEFF) 처럼 목록에서 빠지기 쉬운 문자를 원천 차단하기 위함이다.
		//    공백류(Zs)는 해당하지 않으므로 "archive.tar.gz backup" 같은 정상 파일명은 유지된다.
		//
		//    막는 공격이 둘이다.
		//    - CR/LF: 원본 파일명이 Content-Disposition 헤더나 로그로 흘러갈 때의 주입 경계
		//    - ZWSP 등 보이지 않는 문자: "invoice.exe"+ZWSP 로 확장자 정규화를 실패시켜
		//      "확장자 없음" 으로 빠져나가는 우회
		if (name.codePoints().anyMatch(BIDI_CONTROLS::contains)) {
			return reject(FilenameRejectReason.BIDI_CONTROL);
		}
		if (name.codePoints().anyMatch(FilenameAnalyzer::isControlOrFormat)) {
			return reject(FilenameRejectReason.CONTROL_CHARACTER);
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

		// 8. 길이 상한은 <b>basename 기준</b>으로 판정한다.
		//    일부 브라우저는 "C:\fakepath\" 를 앞에 붙여 보내는데, 전체 길이로 재면
		//    basename 이 255 이하인 정상 파일이 거부된다.
		if (base.codePointCount(0, base.length()) > MAX_BASENAME_LENGTH) {
			return reject(FilenameRejectReason.TOO_LONG);
		}

		// 9. 마지막 확장자와 중간 세그먼트 분리
		return new FilenameAnalysis.Ok(base, lastExtensionOf(base), middleSegmentsOf(base));
	}

	private static boolean isControlOrFormat(int codePoint) {
		int type = Character.getType(codePoint);
		return type == Character.CONTROL || type == Character.FORMAT;
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

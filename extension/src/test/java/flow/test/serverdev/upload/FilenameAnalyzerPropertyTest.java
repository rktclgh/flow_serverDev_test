package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import flow.test.serverdev.policy.ExtensionNormalizer;
import flow.test.serverdev.policy.NormalizeResult;
import flow.test.serverdev.support.RandomText;

/**
 * 파일명 분석의 불변식을 랜덤 입력으로 검증한다.
 *
 * <p>협력자를 모킹하지 않는다. 검증 대상이 두 클래스의 <b>연결</b>이기 때문이다.
 */
class FilenameAnalyzerPropertyTest {

	private static final int TRIES = 3000;

	private final ExtensionNormalizer normalizer = new ExtensionNormalizer();
	private final FilenameAnalyzer analyzer = new FilenameAnalyzer(normalizer);

	@Test
	@DisplayName("어떤 파일명에도 예외를 던지지 않는다")
	void 예외를_던지지_않는다() {
		RandomText.forAll(TRIES, analyzer::analyze);
	}

	@Test
	@DisplayName("★ 추출된 확장자를 재정규화해도 같다 — 정책 매칭의 전제")
	void 추출값은_정규화된_값이다() {
		// 이것이 깨지면 차단 목록에 sh 가 있어도 특정 파일명이 통과하고,
		// 로그에는 정상 업로드로 남는다.
		RandomText.forAll(TRIES, raw -> {
			if (analyzer.analyze(raw) instanceof FilenameAnalysis.Ok ok && ok.lastExtension().isPresent()) {
				String extension = ok.lastExtension().get();
				assertThat(normalizer.normalize(extension))
					.as("추출값이 정규화 결과와 다름: %s -> %s", RandomText.describe(raw), extension)
					.isEqualTo(new NormalizeResult.Ok(extension));
			}
		});
	}

	@Test
	@DisplayName("중간 세그먼트도 정규화된 값이다")
	void 중간_세그먼트_일관성() {
		RandomText.forAll(TRIES, raw -> {
			if (analyzer.analyze(raw) instanceof FilenameAnalysis.Ok ok) {
				for (String segment : ok.middleSegments()) {
					assertThat(normalizer.normalize(segment)).isEqualTo(new NormalizeResult.Ok(segment));
				}
			}
		});
	}

	@Test
	@DisplayName("safeName 에 경로 구분자가 남지 않는다")
	void 경로_구분자_제거_확인() {
		RandomText.forAll(TRIES, raw -> {
			if (analyzer.analyze(raw) instanceof FilenameAnalysis.Ok ok) {
				assertThat(ok.safeName())
					.as("경로 구분자 잔존: %s", RandomText.describe(raw))
					.doesNotContain("/").doesNotContain("\\");
			}
		});
	}

	@Test
	@DisplayName("safeName 에 제어문자·서식문자가 남지 않는다")
	void 제어문자_제거_확인() {
		RandomText.forAll(TRIES, raw -> {
			if (analyzer.analyze(raw) instanceof FilenameAnalysis.Ok ok) {
				boolean hasControlOrFormat = ok.safeName().codePoints().anyMatch(codePoint -> {
					int type = Character.getType(codePoint);
					return type == Character.CONTROL || type == Character.FORMAT;
				});
				assertThat(hasControlOrFormat)
					.as("제어/서식 문자 잔존: %s", RandomText.describe(raw))
					.isFalse();
			}
		});
	}
}

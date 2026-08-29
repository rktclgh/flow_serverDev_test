package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import flow.test.serverdev.policy.ExtensionNormalizer;
import flow.test.serverdev.policy.NormalizeResult;

/**
 * 파일명 분석의 불변식을 랜덤 입력으로 검증한다.
 *
 * <p>협력자를 모킹하지 않는다. 검증 대상이 두 클래스의 <b>연결</b>이기 때문이다.
 */
class FilenameAnalyzerPropertyTest {

	private final ExtensionNormalizer normalizer = new ExtensionNormalizer();
	private final FilenameAnalyzer analyzer = new FilenameAnalyzer(normalizer);

	@Provide
	Arbitrary<String> anyFilename() {
		return Arbitraries.strings().all().ofMaxLength(60);
	}

	@Property(tries = 2000)
	void 어떤_파일명에도_예외를_던지지_않는다(@ForAll @From("anyFilename") String raw) {
		analyzer.analyze(raw);
	}

	/**
	 * ★ 정책 매칭의 전제.
	 *
	 * <p>추출된 확장자를 다시 정규화해도 같아야 정책 저장값과 대조가 성립한다.
	 * 이것이 깨지면 {@code sh} 를 차단해도 특정 파일명이 통과한다.
	 */
	@Property(tries = 2000)
	void 추출된_확장자는_재정규화해도_같다(@ForAll @From("anyFilename") String raw) {
		if (analyzer.analyze(raw) instanceof FilenameAnalysis.Ok ok && ok.lastExtension().isPresent()) {
			String extension = ok.lastExtension().get();
			assertThat(normalizer.normalize(extension))
				.as("추출값이 정규화 결과와 다름: %s -> %s", raw, extension)
				.isEqualTo(new NormalizeResult.Ok(extension));
		}
	}

	@Property(tries = 2000)
	void 중간_세그먼트도_정규화된_값이다(@ForAll @From("anyFilename") String raw) {
		if (analyzer.analyze(raw) instanceof FilenameAnalysis.Ok ok) {
			for (String segment : ok.middleSegments()) {
				assertThat(normalizer.normalize(segment)).isEqualTo(new NormalizeResult.Ok(segment));
			}
		}
	}

	@Property(tries = 2000)
	void safeName_에는_경로_구분자가_남지_않는다(@ForAll @From("anyFilename") String raw) {
		if (analyzer.analyze(raw) instanceof FilenameAnalysis.Ok ok) {
			assertThat(ok.safeName())
				.as("경로 구분자 잔존: %s", raw)
				.doesNotContain("/").doesNotContain("\\");
		}
	}

	/** 성공한 분석 결과에는 제어문자(Cc)·서식문자(Cf)가 남아 있으면 안 된다. */
	@Property(tries = 2000)
	void safeName_에는_제어문자가_남지_않는다(@ForAll @From("anyFilename") String raw) {
		if (analyzer.analyze(raw) instanceof FilenameAnalysis.Ok ok) {
			boolean hasControlOrFormat = ok.safeName().codePoints().anyMatch(cp -> {
				int type = Character.getType(cp);
				return type == Character.CONTROL || type == Character.FORMAT;
			});
			assertThat(hasControlOrFormat).as("제어/서식 문자 잔존: %s", raw).isFalse();
		}
	}
}

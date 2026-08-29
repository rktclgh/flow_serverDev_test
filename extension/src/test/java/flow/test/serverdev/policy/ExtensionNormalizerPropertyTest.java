package flow.test.serverdev.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 케이스 기반 테스트가 잡지 못하는 <b>불변식</b>을 랜덤 입력으로 검증한다.
 *
 * <p>예제 테스트는 "내가 생각한 입력"만 확인한다. 파일명과 확장자는 공격자가 통제하는
 * 입력이므로, 생각하지 못한 입력을 도구가 찾아내도록 한다.
 */
class ExtensionNormalizerPropertyTest {

	private static final Pattern ALLOWED = Pattern.compile("^[a-z0-9]{1,20}$");

	private final ExtensionNormalizer normalizer = new ExtensionNormalizer();

	/** 유니코드 전 범위. 제어문자·서로게이트·전각·결합문자가 모두 나온다. */
	@Provide
	Arbitrary<String> anyText() {
		return Arbitraries.strings().all().ofMaxLength(40);
	}

	@Property(tries = 2000)
	void 어떤_입력에도_예외를_던지지_않는다(@ForAll @From("anyText") String raw) {
		normalizer.normalize(raw);   // 예외가 나면 그 자체로 실패
	}

	@Property(tries = 2000)
	void 성공하면_값은_항상_허용_패턴을_만족한다(@ForAll @From("anyText") String raw) {
		if (normalizer.normalize(raw) instanceof NormalizeResult.Ok ok) {
			assertThat(ALLOWED.matcher(ok.value()).matches())
				.as("정규화 성공값이 패턴을 벗어남: %s", ok.value())
				.isTrue();
		}
	}

	/**
	 * ★ 멱등성 — 이 시스템에서 가장 중요한 불변식.
	 *
	 * <p>정책 저장 시 {@code ".SH "} 를 {@code sh} 로 저장하고, 업로드 검증 시
	 * 파일명에서 뽑은 확장자를 <b>다시 정규화</b>해 대조한다.
	 * 멱등이 아니면 저장값과 검증값이 어긋나 정책이 조용히 새어나간다.
	 *
	 * <p>NFKC 자체는 유니코드 표준이 멱등을 보장하지만, 우리 파이프라인은
	 * NFKC + strip + 점 제거 + lowercase 의 <b>조합</b>이다. 조합의 멱등성은 표준이
	 * 보장하지 않으므로 직접 검증한다.
	 */
	@Property(tries = 2000)
	void 정규화는_멱등이다(@ForAll @From("anyText") String raw) {
		if (normalizer.normalize(raw) instanceof NormalizeResult.Ok first) {
			NormalizeResult second = normalizer.normalize(first.value());
			assertThat(second)
				.as("멱등성 위반: %s -> %s -> %s", raw, first.value(), second)
				.isEqualTo(first);
		}
	}

	@Property(tries = 1000)
	void 이미_정규화된_값은_그대로_통과한다(@ForAll("normalizedValues") String value) {
		assertThat(normalizer.normalize(value)).isEqualTo(new NormalizeResult.Ok(value));
	}

	@Provide
	Arbitrary<String> normalizedValues() {
		return Arbitraries.strings().withCharRange('a', 'z').withCharRange('0', '9')
			.ofMinLength(1).ofMaxLength(20);
	}
}

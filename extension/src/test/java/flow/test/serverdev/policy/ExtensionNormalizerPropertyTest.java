package flow.test.serverdev.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import flow.test.serverdev.support.RandomText;

/**
 * 케이스 기반 테스트가 잡지 못하는 <b>불변식</b>을 랜덤 입력으로 검증한다.
 *
 * <p>예제 테스트는 "내가 생각한 입력" 만 확인한다. 확장자와 파일명은 공격자가 통제하는
 * 입력이므로, 생각하지 못한 입력을 도구가 찾아내도록 한다.
 *
 * <p>외부 property 라이브러리를 쓰지 않는 이유는 {@link RandomText} 주석에 있다.
 */
class ExtensionNormalizerPropertyTest {

	private static final int TRIES = 3000;
	private static final Pattern ALLOWED = Pattern.compile("^[a-z0-9]{1,20}$");

	private final ExtensionNormalizer normalizer = new ExtensionNormalizer();

	@Test
	@DisplayName("어떤 입력에도 예외를 던지지 않는다")
	void 예외를_던지지_않는다() {
		// 공격자가 보낸 입력으로 500 을 유발할 수 없어야 한다.
		RandomText.forAll(TRIES, normalizer::normalize);
	}

	@Test
	@DisplayName("정규화에 성공하면 값은 항상 허용 패턴을 만족한다")
	void 출력_계약() {
		RandomText.forAll(TRIES, raw -> {
			if (normalizer.normalize(raw) instanceof NormalizeResult.Ok ok) {
				assertThat(ALLOWED.matcher(ok.value()).matches())
					.as("정규화 성공값이 패턴을 벗어남: %s", RandomText.describe(ok.value()))
					.isTrue();
			}
		});
	}

	@Test
	@DisplayName("★ 정규화는 멱등이다 — 정책 저장값과 업로드 검증값이 일치하기 위한 전제")
	void 멱등성() {
		// 정책 저장 시 ".SH " 를 "sh" 로 저장하고, 업로드 검증 시 파일명에서 뽑은 확장자를
		// 다시 정규화해 대조한다. 멱등이 아니면 저장값과 검증값이 어긋나 정책이 조용히 새어나간다.
		//
		// NFKC 자체의 멱등성은 유니코드 표준이 보장하지만, 본 구현은
		// NFKC + strip + 점 제거 + lowercase 의 '조합' 이며 조합의 멱등성은 표준이 보장하지 않는다.
		RandomText.forAll(TRIES, raw -> {
			if (normalizer.normalize(raw) instanceof NormalizeResult.Ok first) {
				assertThat(normalizer.normalize(first.value()))
					.as("멱등성 위반: %s -> %s", RandomText.describe(raw), first.value())
					.isEqualTo(first);
			}
		});
	}

	@Test
	@DisplayName("이미 정규화된 값은 그대로 통과한다")
	void 정규화된_값은_불변() {
		Random random = new Random(4242);   // 고정 seed — 이 테스트는 재현이 목적이다
		for (int i = 0; i < 1000; i++) {
			String value = randomAllowedValue(random);
			assertThat(normalizer.normalize(value)).isEqualTo(new NormalizeResult.Ok(value));
		}
	}

	private static String randomAllowedValue(Random random) {
		int length = 1 + random.nextInt(20);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < length; i++) {
			builder.append(random.nextBoolean()
				? (char) ('a' + random.nextInt(26))
				: (char) ('0' + random.nextInt(10)));
		}
		return builder.toString();
	}
}

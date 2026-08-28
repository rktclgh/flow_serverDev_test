package flow.test.serverdev.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import flow.test.serverdev.policy.NormalizeResult.Ok;
import flow.test.serverdev.policy.NormalizeResult.Rejected;

/**
 * SPEC §4 의 케이스 표를 그대로 옮긴 테스트.
 *
 * <p>이 클래스는 DB·스프링 컨텍스트에 의존하지 않는 순수 단위 테스트다.
 * 정규화가 이 과제의 심장이므로 케이스를 빠짐없이 고정한다.
 */
class ExtensionNormalizerTest {

	private final ExtensionNormalizer normalizer = new ExtensionNormalizer();

	// 눈에 보이지 않는 문자를 소스에 직접 넣지 않는다.
	// 에디터가 지우거나, 리뷰에서 발견되지 않거나, 다른 공백으로 오인될 수 있다.
	private static final String NBSP = "\u00A0";  // NFKC 가 일반 공백(U+0020)으로 접는다
	private static final String NUL = "\u0000";

	private String okValue(String raw) {
		NormalizeResult result = normalizer.normalize(raw);
		assertThat(result).isInstanceOf(Ok.class);
		return ((Ok) result).value();
	}

	private RejectReason rejectReason(String raw) {
		NormalizeResult result = normalizer.normalize(raw);
		assertThat(result).isInstanceOf(Rejected.class);
		return ((Rejected) result).reason();
	}

	@Nested
	@DisplayName("정규화 성공")
	class Accepted {

		@Test
		@DisplayName("이미 정규화된 값은 그대로 통과한다")
		void 정규화된_값은_그대로() {
			assertThat(okValue("exe")).isEqualTo("exe");
		}

		@ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
		@CsvSource({
			"EXE, exe",
			"Exe, exe",
			"eXe, exe",
		})
		@DisplayName("대소문자를 소문자로 접는다")
		void 대소문자_정규화(String raw, String expected) {
			assertThat(okValue(raw)).isEqualTo(expected);
		}

		@Test
		@DisplayName("선행 점 하나는 제거한다")
		void 선행_점_제거() {
			assertThat(okValue(".sh")).isEqualTo("sh");
		}

		@ParameterizedTest(name = "\"{0}\"")
		@ValueSource(strings = { "exe.", "exe ", "exe. ", "exe .", "exe   " })
		@DisplayName("후행 점과 공백을 제거한다 — Windows 가 exe. 을 exe 로 해석하기 때문")
		void 후행_점과_공백_제거(String raw) {
			assertThat(okValue(raw)).isEqualTo("exe");
		}

		@Test
		@DisplayName("전각 문자를 NFKC 로 접는다")
		void 전각_문자_NFKC() {
			assertThat(okValue("ｅｘｅ")).isEqualTo("exe");
		}

		@Test
		@DisplayName("NBSP 는 NFKC 가 일반 공백으로 접으므로 strip 이 제거한다")
		void 비분리공백_처리() {
			// String.strip() 은 Character.isWhitespace 기반이라 U+00A0 를 제거하지 못한다.
			// NFKC 를 strip 보다 먼저 적용해야만 이 케이스가 통과한다 — 단계 순서에 대한 회귀 테스트다.
			assertThat(okValue("exe" + NBSP)).isEqualTo("exe");
			assertThat(okValue(NBSP + "exe")).isEqualTo("exe");
		}

		@Test
		@DisplayName("터키어 로케일에서도 대문자 I 가 올바르게 소문자화된다")
		void 터키어_로케일_안전성() {
			// Locale 을 지정하지 않으면 터키어에서 "I" -> "ı"(U+0131) 가 되어
			// 고정 확장자 js 가 조용히 뚫린다. toLowerCase(Locale.ROOT) 강제에 대한 회귀 테스트.
			Locale original = Locale.getDefault();
			try {
				Locale.setDefault(Locale.forLanguageTag("tr"));
				assertThat(okValue("JS")).isEqualTo("js");
			} finally {
				Locale.setDefault(original);
			}
		}

		@Test
		@DisplayName("숫자와 영소문자 조합을 허용한다")
		void 영숫자_허용() {
			assertThat(okValue("mp4")).isEqualTo("mp4");
			assertThat(okValue("7z")).isEqualTo("7z");
		}

		@Test
		@DisplayName("경계값 20자는 통과한다")
		void 경계값_20자() {
			String twenty = "a".repeat(20);
			assertThat(okValue(twenty)).isEqualTo(twenty);
		}
	}

	@Nested
	@DisplayName("정규화 거부")
	class Denied {

		@ParameterizedTest(name = "\"{0}\"")
		@ValueSource(strings = { "", "   ", ".", "\u00A0" })
		@DisplayName("빈 값·공백·점만 있는 값은 EMPTY 로 거부한다")
		void 빈_값_거부(String raw) {
			assertThat(rejectReason(raw)).isEqualTo(RejectReason.EMPTY);
		}

		@Test
		@DisplayName("null 은 EMPTY 로 거부한다")
		void null_거부() {
			assertThat(rejectReason(null)).isEqualTo(RejectReason.EMPTY);
		}

		@Test
		@DisplayName("21자는 TOO_LONG 으로 거부한다 — 형식 오류와 구분해야 안내가 달라진다")
		void 길이_초과_거부() {
			assertThat(rejectReason("a".repeat(21))).isEqualTo(RejectReason.TOO_LONG);
		}

		@ParameterizedTest(name = "\"{0}\"")
		@ValueSource(strings = { "..sh", "...", ".." })
		@DisplayName("선행 점이 둘 이상이면 거부한다 — 하나만 제거하므로 남은 점이 걸린다")
		void 다중_선행_점_거부(String raw) {
			assertThat(rejectReason(raw)).isNotNull();
		}

		@ParameterizedTest(name = "\"{0}\"")
		@ValueSource(strings = { "e x e", "ex/e", "ex\\e", "ex:e", "ex*e", "ex?e" })
		@DisplayName("내부 공백과 경로·특수 문자는 INVALID_CHARACTER 로 거부한다")
		void 특수문자_거부(String raw) {
			assertThat(rejectReason(raw)).isEqualTo(RejectReason.INVALID_CHARACTER);
		}

		@Test
		@DisplayName("널바이트가 섞이면 거부한다")
		void 널바이트_거부() {
			assertThat(rejectReason("exe" + NUL)).isEqualTo(RejectReason.INVALID_CHARACTER);
			assertThat(rejectReason("e" + NUL + "xe")).isEqualTo(RejectReason.INVALID_CHARACTER);
		}

		@Test
		@DisplayName("비ASCII 문자는 거부한다")
		void 비아스키_거부() {
			assertThat(rejectReason("한글")).isEqualTo(RejectReason.INVALID_CHARACTER);
			assertThat(rejectReason("café")).isEqualTo(RejectReason.INVALID_CHARACTER);
		}
	}
}

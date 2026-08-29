package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 기록용 파일명 변환.
 *
 * <p><b>소스에 보이지 않는 문자를 리터럴로 넣지 않는다.</b> 도구가 막기도 하지만,
 * 무엇보다 다음 사람이 이 파일을 읽을 때 거기 무엇이 들어 있는지 알 수 없다.
 * 경계 문자는 코드포인트로 선언해 이름과 값이 함께 보이게 한다.
 */
@DisplayName("기록용 파일명")
class AuditFilenamesTest {

	private static final String NUL = Character.toString(0x0000);
	private static final String ZWSP = Character.toString(0x200B);
	private static final String BOM = Character.toString(0xFEFF);
	private static final String RTL_OVERRIDE = Character.toString(0x202E);
	private static final String CAMERA = Character.toString(0x1F4F7);

	@Nested
	@DisplayName("보이지 않는 문자를 보이게 만든다")
	class MakesInvisibleVisible {

		@Test
		@DisplayName("널바이트")
		void nullByte() {
			assertThat(AuditFilenames.forRecord("safe.jpg" + NUL + ".exe"))
				.isEqualTo("safe.jpg\\u0000.exe");
		}

		/** 로그 한 줄을 두 줄로 쪼개 가짜 항목을 만드는 경로를 닫는다. */
		@Test
		@DisplayName("CR/LF — 로그 주입 경로")
		void crlf() {
			assertThat(AuditFilenames.forRecord("a.txt\r\nfake log line"))
				.isEqualTo("a.txt\\u000d\\u000afake log line");
		}

		/** 화면에서는 photoexe.png 로 보인다. 조사하는 사람이 잘못 읽게 된다. */
		@Test
		@DisplayName("RTL 재정의 — 시각적 위장")
		void rtlOverride() {
			assertThat(AuditFilenames.forRecord("photo" + RTL_OVERRIDE + "gnp.exe"))
				.isEqualTo("photo\\u202egnp.exe");
		}

		@Test
		@DisplayName("폭 없는 공백")
		void zeroWidthSpace() {
			assertThat(AuditFilenames.forRecord("invoice.exe" + ZWSP))
				.isEqualTo("invoice.exe\\u200b");
		}

		@Test
		@DisplayName("BOM")
		void byteOrderMark() {
			assertThat(AuditFilenames.forRecord(BOM + "a.txt")).isEqualTo("\\ufeffa.txt");
		}
	}

	@Nested
	@DisplayName("정상 문자는 그대로 둔다")
	class PreservesNormalText {

		@Test
		@DisplayName("평범한 파일명")
		void plain() {
			assertThat(AuditFilenames.forRecord("report.pdf")).isEqualTo("report.pdf");
		}

		@Test
		@DisplayName("한글과 공백")
		void korean() {
			assertThat(AuditFilenames.forRecord("보고서 최종.pdf")).isEqualTo("보고서 최종.pdf");
		}

		/** 이모지는 서로게이트 쌍이다. 코드포인트 단위로 다루지 않으면 깨진다. */
		@Test
		@DisplayName("이모지가 깨지지 않는다")
		void emoji() {
			assertThat(AuditFilenames.forRecord("사진" + CAMERA + ".png"))
				.isEqualTo("사진" + CAMERA + ".png");
		}

		@Test
		@DisplayName("null 은 빈 문자열이 된다 — 컬럼이 NOT NULL 이다")
		void nullBecomesEmpty() {
			assertThat(AuditFilenames.forRecord(null)).isEmpty();
		}
	}

	@Nested
	@DisplayName("길이 상한")
	class Truncation {

		/**
		 * 파일명은 최대 4096자까지 들어오고, 제어문자로 가득하면 6배로 부푼다.
		 * 요청 하나로 감사 테이블에 24KB 를 쓰게 만들 수 있다는 뜻이다.
		 * 증거로서의 가치는 앞부분에 있다.
		 */
		@Test
		@DisplayName("이스케이프로 부풀어도 상한을 넘지 않는다")
		void escapingCannotBlowUpTheRow() {
			String hostile = NUL.repeat(4096);

			String recorded = AuditFilenames.forRecord(hostile);

			assertThat(recorded).hasSizeLessThanOrEqualTo(AuditFilenames.MAX_LENGTH);
			assertThat(recorded).endsWith("(truncated)");
		}

		@Test
		@DisplayName("상한 이하이면 그대로 둔다")
		void underLimitIsUntouched() {
			String name = "a".repeat(AuditFilenames.MAX_LENGTH);

			assertThat(AuditFilenames.forRecord(name)).isEqualTo(name);
		}
	}
}

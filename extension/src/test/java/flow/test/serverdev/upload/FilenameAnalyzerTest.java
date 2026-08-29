package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import flow.test.serverdev.policy.ExtensionNormalizer;
import flow.test.serverdev.upload.FilenameAnalysis.Ok;
import flow.test.serverdev.upload.FilenameAnalysis.Rejected;

/**
 * SPEC §5 의 케이스를 고정한 테스트.
 *
 * <p>DB·스프링 컨텍스트에 의존하지 않는 순수 단위 테스트다.
 */
class FilenameAnalyzerTest {

	// 협력자를 모킹하지 않고 실제 ExtensionNormalizer 를 쓴다.
	// 정규화 규칙이 바뀌면 파일명 분석 결과도 함께 바뀌어야 하고, 그 연결이 테스트로 확인되어야 한다.
	private final FilenameAnalyzer analyzer = new FilenameAnalyzer(new ExtensionNormalizer());

	// 눈에 보이지 않는 문자를 소스에 직접 넣지 않는다.
	private static final String NUL = "\u0000";
	private static final String RLO = "\u202E";   // RIGHT-TO-LEFT OVERRIDE
	private static final String LRE = "\u202A";   // LEFT-TO-RIGHT EMBEDDING
	private static final String RLM = "\u200F";   // RIGHT-TO-LEFT MARK
	private static final String FW_DOT = "\uFF0E"; // 전각 마침표. NFKC 로 ASCII 점이 된다
	private static final String ALM = "\u061C";   // ARABIC LETTER MARK — bidi 제어인데 목록에서 빠지기 쉽다
	private static final String ZWSP = "\u200B";  // ZERO WIDTH SPACE — Cf, 화면에 보이지 않는다
	private static final String BOM = "\uFEFF";   // ZERO WIDTH NO-BREAK SPACE / BOM — Cf
	private static final String CR = "\r";
	private static final String LF = "\n";

	private Ok ok(String raw) {
		FilenameAnalysis result = analyzer.analyze(raw);
		assertThat(result).isInstanceOf(Ok.class);
		return (Ok) result;
	}

	private String ext(String raw) {
		return ok(raw).lastExtension().orElse(null);
	}

	private FilenameRejectReason reason(String raw) {
		FilenameAnalysis result = analyzer.analyze(raw);
		assertThat(result).isInstanceOf(Rejected.class);
		return ((Rejected) result).reason();
	}

	@Nested
	@DisplayName("확장자 추출")
	class ExtensionExtraction {

		@ParameterizedTest(name = "{0} -> {1}")
		@CsvSource({
			"report.pdf, pdf",
			"photo.PNG, png",
			"archive.tar.gz, gz",
			"data.MP4, mp4",
		})
		@DisplayName("마지막 확장자를 정규화해 반환한다")
		void 마지막_확장자_추출(String filename, String expected) {
			assertThat(ext(filename)).isEqualTo(expected);
		}

		@Test
		@DisplayName("확장자가 없으면 비어 있다")
		void 확장자_없음() {
			assertThat(ok("Makefile").lastExtension()).isEmpty();
			assertThat(ok("LICENSE").lastExtension()).isEmpty();
		}

		@Test
		@DisplayName("점으로 시작하는 숨김 파일도 확장자로 취급한다")
		void 숨김_파일() {
			assertThat(ext(".env")).isEqualTo("env");
			assertThat(ext(".gitignore")).isEqualTo("gitignore");
		}

		@Test
		@DisplayName("확장자로 정규화되지 않는 조각은 확장자가 아니다")
		void 정규화_실패_조각() {
			// "file name" 은 내부 공백이라 확장자가 될 수 없다.
			assertThat(ext("my.file name.txt")).isEqualTo("txt");
			// 마지막 조각 자체가 정규화 불가면 확장자 없음으로 본다.
			assertThat(ok("archive.tar.gz backup").lastExtension()).isEmpty();
		}
	}

	@Nested
	@DisplayName("우회 방어")
	class BypassDefense {

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = { "script.sh.", "script.sh. ", "script.sh .", "script.sh   ." })
		@DisplayName("후행 점·공백이 붙어도 확장자를 찾아낸다 — basename 정리가 확장자 분리보다 먼저다")
		void 후행_점_우회_방어(String filename) {
			// v2 는 후행 점 제거를 확장자 문자열에만 적용해 이 케이스가 "확장자 없음"으로 빠져나갔다.
			// Windows 는 "script.sh." 를 "script.sh" 로 해석하므로 그대로 두면 정책을 우회한다.
			assertThat(ext(filename)).isEqualTo("sh");
		}

		@Test
		@DisplayName("전각 마침표는 NFKC 로 접힌 뒤 후행 점으로 처리된다")
		void 전각_마침표_우회_방어() {
			assertThat(ext("invoice.txt.exe" + FW_DOT)).isEqualTo("exe");
		}

		@Test
		@DisplayName("이중 확장자에서 실제로 실행되는 마지막 확장자를 잡는다")
		void 이중_확장자() {
			// Windows 확장자 숨김은 마지막 확장자만 숨긴다.
			// 따라서 위험한 쪽은 file.txt.exe 이고, 마지막만 봐도 잡힌다.
			assertThat(ext("invoice.txt.exe")).isEqualTo("exe");
			assertThat(ext("photo.jpg.scr")).isEqualTo("scr");
		}

		@ParameterizedTest(name = "{0} -> basename {1}")
		@CsvSource({
			"'../../etc/passwd', passwd",
			"'/var/www/html/shell.php', shell.php",
			"'C:\\Windows\\System32\\evil.exe', evil.exe",
			"'./../secret.key', secret.key",
		})
		@DisplayName("경로 구분자가 섞이면 basename 만 취한다")
		void 경로_구분자_제거(String filename, String expectedBase) {
			assertThat(ok(filename).safeName()).isEqualTo(expectedBase);
		}

		@Test
		@DisplayName("경로 조작 시도에서도 확장자 판정이 유지된다")
		void 경로_조작_확장자() {
			assertThat(ext("../../tmp/evil.exe")).isEqualTo("exe");
		}
	}

	@Nested
	@DisplayName("중간 세그먼트 관측")
	class MiddleSegments {

		@Test
		@DisplayName("중간 세그먼트를 수집하되 차단 판정에는 쓰지 않는다")
		void 중간_세그먼트_수집() {
			Ok result = ok("backup.exe.log");
			assertThat(result.lastExtension()).contains("log");   // 차단 판정 대상
			assertThat(result.middleSegments()).containsExactly("exe"); // 관측 전용
		}

		@Test
		@DisplayName("정상 복합 확장자도 중간 세그먼트로 기록된다")
		void 복합_확장자() {
			Ok result = ok("archive.tar.gz");
			assertThat(result.lastExtension()).contains("gz");
			assertThat(result.middleSegments()).containsExactly("tar");
		}

		@Test
		@DisplayName("세그먼트가 여러 개면 순서대로 수집한다")
		void 다중_세그먼트() {
			assertThat(ok("a.b.c.d").middleSegments()).containsExactly("b", "c");
		}

		@Test
		@DisplayName("중간 세그먼트가 없으면 빈 목록이다")
		void 세그먼트_없음() {
			assertThat(ok("report.pdf").middleSegments()).isEmpty();
			assertThat(ok("Makefile").middleSegments()).isEmpty();
		}
	}

	@Nested
	@DisplayName("파일명 거부")
	class Rejection {

		@ParameterizedTest(name = "\"{0}\"")
		@ValueSource(strings = { "", "   ", "\u00A0" })
		@DisplayName("빈 파일명은 EMPTY 로 거부한다")
		void 빈_파일명(String filename) {
			assertThat(reason(filename)).isEqualTo(FilenameRejectReason.EMPTY);
		}

		@Test
		@DisplayName("null 은 EMPTY 로 거부한다")
		void null_파일명() {
			assertThat(reason(null)).isEqualTo(FilenameRejectReason.EMPTY);
		}

		@Test
		@DisplayName("널바이트는 절단하지 않고 거부한다 — 절단은 공격자 의도대로 동작하는 것")
		void 널바이트_거부() {
			assertThat(reason("safe.jpg" + NUL + ".exe")).isEqualTo(FilenameRejectReason.NULL_BYTE);
			assertThat(reason("report" + NUL)).isEqualTo(FilenameRejectReason.NULL_BYTE);
		}

		@ParameterizedTest(name = "제어문자 포함")
		@ValueSource(strings = { "photo", "invoice" })
		@DisplayName("양방향 제어문자는 거부한다 — 화면 표시와 실제 확장자가 달라진다")
		void 양방향_제어문자_거부(String prefix) {
			// photo + U+202E + "gnp.exe" 는 화면에 photoexe.png 로 보인다.
			assertThat(reason(prefix + RLO + "gnp.exe")).isEqualTo(FilenameRejectReason.BIDI_CONTROL);
			assertThat(reason(prefix + LRE + ".exe")).isEqualTo(FilenameRejectReason.BIDI_CONTROL);
			assertThat(reason(prefix + RLM + ".exe")).isEqualTo(FilenameRejectReason.BIDI_CONTROL);
		}

		@Test
		@DisplayName("255 코드포인트를 넘으면 거부한다")
		void 파일명_길이_초과() {
			assertThat(reason("a".repeat(252) + ".txt")).isEqualTo(FilenameRejectReason.TOO_LONG);
		}

		@Test
		@DisplayName("경계값 255 는 통과한다")
		void 파일명_길이_경계() {
			String name = "a".repeat(251) + ".txt";   // 251 + 4 = 255
			assertThat(ok(name).lastExtension()).contains("txt");
		}

		@Test
		@DisplayName("보이지 않는 서식문자로 확장자 정규화를 실패시키는 우회를 막는다")
		void 보이지_않는_문자_우회_방어() {
			// "invoice.exe" + ZWSP 는 확장자 후보가 "exe"+ZWSP 가 되어 정규화에 실패한다.
			// 그대로 두면 Optional.empty() 가 되어 "확장자 없음" 으로 빠져나가고,
			// Makefile 같은 진짜 확장자 없는 파일과 구분되지 않는다.
			// ZWSP 는 Character.isWhitespace 가 false 라 후행 제거 대상도 아니다.
			assertThat(reason("invoice.exe" + ZWSP)).isEqualTo(FilenameRejectReason.CONTROL_CHARACTER);
			assertThat(reason("invoice" + BOM + ".exe")).isEqualTo(FilenameRejectReason.CONTROL_CHARACTER);
		}

		@Test
		@DisplayName("CR/LF 는 거부한다 — 원본 파일명이 헤더·로그로 흘러갈 때의 주입 경계")
		void 개행문자_거부() {
			assertThat(reason("report" + CR + LF + "X-Forged: yes.txt"))
				.isEqualTo(FilenameRejectReason.CONTROL_CHARACTER);
			assertThat(reason("report" + LF + ".txt")).isEqualTo(FilenameRejectReason.CONTROL_CHARACTER);
		}

		@Test
		@DisplayName("ALM(U+061C) 도 양방향 제어문자로 거부한다")
		void 아랍문자표시_거부() {
			// 개별 코드포인트를 나열하면 빠지기 쉬운 문자다.
			// 유니코드 카테고리(Cf) 기반 판정이라 목록 누락과 무관하게 걸린다.
			assertThat(reason("photo" + ALM + "gnp.exe")).isEqualTo(FilenameRejectReason.BIDI_CONTROL);
		}

		@Test
		@DisplayName("공백(Zs)은 제어문자가 아니므로 정상 파일명을 거부하지 않는다")
		void 공백은_허용() {
			// Cc/Cf 만 거부한다. 사람이 붙인 "my report.pdf" 같은 이름은 유지되어야 한다.
			assertThat(ext("my report.pdf")).isEqualTo("pdf");
			assertThat(ok("archive.tar.gz backup").lastExtension()).isEmpty();
		}

		@Test
		@DisplayName("원시 입력 상한이 적용된다 (동작 고정 — 회귀 방어가 아님)")
		void 원시_입력_상한() {
			// ★ 이 테스트는 상한이 "정규화 이전에" 적용되는지 증명하지 못한다.
			//    상한을 제거해도 basename 길이 검사가 같은 TOO_LONG 을 내므로 결과가 동일하다.
			//    뮤테이션 검증에서 확인했다 — 원시 상한 제거 시 실패한 테스트 0건.
			//
			//    원시 상한의 목적은 정확성이 아니라 비용 상한이다. 공격자가 통제하는 거대한
			//    문자열을 NFKC 로 전부 정규화하고 코드포인트를 순회한 뒤에야 거부하는 것을 막는다.
			//    그 성능 특성은 결과 기반 테스트로 검증할 수 없고(시간 측정은 CI 에서 불안정하다),
			//    코드의 단계 순서로만 보장된다.
			assertThat(reason("a".repeat(5000) + ".txt")).isEqualTo(FilenameRejectReason.TOO_LONG);
		}

		@Test
		@DisplayName("길이는 basename 기준으로 잰다 — C:\\fakepath\\ 접두어가 정상 파일을 거부하면 안 된다")
		void 길이는_basename_기준() {
			// 일부 브라우저는 C:\fakepath\ 를 붙여 보낸다.
			// 전체 길이로 재면 267자가 되어 거부되지만 basename 은 255자로 정상이다.
			String withPath = "C:\\fakepath\\" + "a".repeat(251) + ".txt";
			assertThat(ok(withPath).lastExtension()).contains("txt");
		}
	}
}

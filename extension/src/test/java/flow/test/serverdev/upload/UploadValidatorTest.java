package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.policy.BlockedExtensionRepository;
import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 업로드 판정. (SPEC §21.4)
 *
 * <p>실제 정책 테이블 위에서 검증한다. 저장소를 가짜로 바꾸면 <b>정책 조회가 실제로 무엇을
 * 돌려주는지</b>를 내 상상으로 대체하게 되고, 이 클래스가 하는 일의 절반이 그 조회다.
 *
 * <p>개별 거부 사유뿐 아니라 <b>두 사유가 동시에 성립할 때 무엇이 이기는지</b>도 고정한다.
 * 순서가 명세이므로 순서가 바뀌면 사용자가 받는 답이 바뀐다.
 */
@DisplayName("업로드 판정")
class UploadValidatorTest extends IntegrationTest {

	/** 소스에 리터럴 제어문자를 넣지 않는다. */
	private static final String NUL = String.valueOf((char) 0);

	private static final byte[] HARMLESS = "hello".getBytes(StandardCharsets.UTF_8);
	private static final byte[] WINDOWS_PE = { 0x4D, 0x5A, (byte) 0x90, 0x00 };

	@Autowired
	private UploadValidator validator;

	@Autowired
	private FilenameAnalyzer filenameAnalyzer;

	@Autowired
	private BlockedExtensionRepository repository;

	@Autowired
	private SignatureInspector signatureInspector;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void resetPolicy() {
		PolicyFixture.reset(jdbc);
	}

	private void block(String name) {
		jdbc.update("UPDATE blocked_extension SET is_blocked = TRUE WHERE name = ?", name);
	}

	private static ErrorCode codeOf(UploadDecision decision) {
		return ((UploadDecision.Rejected) decision).code();
	}

	@Nested
	@DisplayName("통과")
	class Accepted {

		@Test
		@DisplayName("차단 목록에 없는 확장자는 통과한다")
		void plainFile() {
			UploadDecision decision = validator.validate("report.pdf", 20481, HARMLESS);

			assertThat(decision).isInstanceOf(UploadDecision.Accepted.class);
			assertThat(((UploadDecision.Accepted) decision).extension()).contains("pdf");
		}

		@Test
		@DisplayName("체크가 해제된 고정 확장자는 차단하지 않는다 — 목록에 있는 것과 차단된 것은 다르다")
		void fixedButUnchecked() {
			assertThat(validator.validate("setup.exe", 10, HARMLESS))
				.isInstanceOf(UploadDecision.Accepted.class);
		}
	}

	@Nested
	@DisplayName("거부")
	class Rejected {

		@Test
		@DisplayName("파일명 255자 초과")
		void tooLong() {
			String name = "a".repeat(256) + ".pdf";

			assertThat(codeOf(validator.validate(name, 10, HARMLESS)))
				.isEqualTo(ErrorCode.FILE_NAME_TOO_LONG);
		}

		@Test
		@DisplayName("널바이트가 섞인 파일명")
		void nullByte() {
			assertThat(codeOf(validator.validate("re" + NUL + "port.pdf", 10, HARMLESS)))
				.isEqualTo(ErrorCode.FILE_NAME_INVALID);
		}

		@Test
		@DisplayName("빈 파일")
		void empty() {
			assertThat(codeOf(validator.validate("report.pdf", 0, new byte[0])))
				.isEqualTo(ErrorCode.FILE_EMPTY);
		}

		@Test
		@DisplayName("확장자 없는 파일은 기본 차단")
		void extensionless() {
			assertThat(codeOf(validator.validate("README", 10, HARMLESS)))
				.isEqualTo(ErrorCode.FILE_EXTENSION_MISSING);
		}

		@Test
		@DisplayName("차단된 확장자 — 무엇이 왜 걸렸는지 detail 에 담는다")
		void blockedExtension() {
			block("exe");

			UploadDecision decision = validator.validate("setup.exe", 10, HARMLESS);

			assertThat(codeOf(decision)).isEqualTo(ErrorCode.FILE_BLOCKED_EXTENSION);
			assertThat(((UploadDecision.Rejected) decision).detail())
				.containsEntry("blockedExtension", "exe")
				.containsEntry("policyType", "FIXED");
		}

		@Test
		@DisplayName("이름은 멀쩡한데 내용이 실행 파일")
		void executableContent() {
			UploadDecision decision = validator.validate("photo.jpg", 10, WINDOWS_PE);

			assertThat(codeOf(decision)).isEqualTo(ErrorCode.FILE_EXECUTABLE_CONTENT);
			assertThat(((UploadDecision.Rejected) decision).detail())
				.containsEntry("signature", ExecutableSignature.WINDOWS_PE.name());
		}
	}

	@Nested
	@DisplayName("두 사유가 동시에 성립할 때")
	class Precedence {

		@Test
		@DisplayName("파일명 오류가 확장자 차단을 이긴다 — 이름을 못 믿으면 확장자도 못 믿는다")
		void nameBeatsExtension() {
			block("exe");

			assertThat(codeOf(validator.validate("set" + NUL + "up.exe", 10, HARMLESS)))
				.isEqualTo(ErrorCode.FILE_NAME_INVALID);
		}

		@Test
		@DisplayName("빈 파일이 확장자 차단을 이긴다 — 내용이 없으면 판정할 것도 없다")
		void emptyBeatsExtension() {
			block("exe");

			assertThat(codeOf(validator.validate("setup.exe", 0, new byte[0])))
				.isEqualTo(ErrorCode.FILE_EMPTY);
		}

		/**
		 * SPEC §21.4 가 한계로 적어둔 동작을 <b>테스트로 고정</b>한다. 확장자에서 걸리면 시그니처를
		 * 보지 않으므로 감사에 "실행 파일" 신호가 남지 않는다. 의도한 결과이며, 바뀌면 여기서 깨진다.
		 */
		@Test
		@DisplayName("확장자 차단이 실행 시그니처를 이긴다 — 싼 검사가 먼저다")
		void extensionBeatsSignature() {
			block("exe");

			assertThat(codeOf(validator.validate("setup.exe", 10, WINDOWS_PE)))
				.isEqualTo(ErrorCode.FILE_BLOCKED_EXTENSION);
		}
	}

	@Nested
	@DisplayName("확장자 없는 파일 허용 설정")
	class Extensionless {

		private UploadValidator permissive() {
			return new UploadValidator(filenameAnalyzer, repository, signatureInspector, true);
		}

		@Test
		@DisplayName("켜면 확장자가 없어도 통과한다")
		void allowed() {
			assertThat(permissive().validate("README", 10, HARMLESS))
				.isInstanceOf(UploadDecision.Accepted.class);
		}

		@Test
		@DisplayName("켜도 내용 검사는 그대로 한다 — 확장자 허용이 검사 면제가 아니다")
		void stillInspectsContent() {
			assertThat(codeOf(permissive().validate("README", 10, WINDOWS_PE)))
				.isEqualTo(ErrorCode.FILE_EXECUTABLE_CONTENT);
		}
	}
}

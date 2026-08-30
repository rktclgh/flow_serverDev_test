package flow.test.serverdev.upload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 매직넘버 검사. (SPEC §6)
 *
 * <p>확장자는 이름일 뿐이라 {@code cp malware.exe report.jpg} 한 줄로 바꿀 수 있지만
 * 내용은 바꿀 수 없다. <b>이름이 아니라 내용을 보는 검사</b>다.
 *
 * <p>선두 4바이트만 읽으므로 파일 크기와 무관하게 비용이 고정이다.
 */
@DisplayName("실행 파일 시그니처 검사")
class SignatureInspectorTest {

	private final SignatureInspector inspector = new SignatureInspector();

	@Nested
	@DisplayName("탐지")
	class Detected {

		@Test
		@DisplayName("MZ 는 Windows 실행 파일이다")
		void windowsPe() {
			assertThat(inspector.detect(bytes(0x4D, 0x5A, 0x90, 0x00)))
				.contains(ExecutableSignature.WINDOWS_PE);
		}

		@Test
		@DisplayName("7F 45 4C 46 은 ELF 다")
		void elf() {
			assertThat(inspector.detect(bytes(0x7F, 0x45, 0x4C, 0x46)))
				.contains(ExecutableSignature.ELF);
		}

		@Test
		@DisplayName("#! 는 스크립트다")
		void shebang() {
			assertThat(inspector.detect("#!/bin/sh\necho hi".getBytes()))
				.contains(ExecutableSignature.SHEBANG);
		}

		/** 시그니처는 선두 2~4바이트다. 그 뒤에 무엇이 오든 판정은 같다. */
		@Test
		@DisplayName("뒤에 내용이 길어도 선두만 본다")
		void onlyPrefixMatters() {
			byte[] large = new byte[8192];
			large[0] = 0x4D;
			large[1] = 0x5A;

			assertThat(inspector.detect(large)).contains(ExecutableSignature.WINDOWS_PE);
		}

		/** MZ 는 2바이트다. 4바이트를 다 못 읽었어도 판정할 수 있다. */
		@Test
		@DisplayName("2바이트만 있어도 MZ 는 판정된다")
		void shortButSufficient() {
			assertThat(inspector.detect(bytes(0x4D, 0x5A)))
				.contains(ExecutableSignature.WINDOWS_PE);
		}
	}

	@Nested
	@DisplayName("비탐지")
	class NotDetected {

		@Test
		@DisplayName("JPEG 은 실행 파일이 아니다")
		void jpeg() {
			assertThat(inspector.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEmpty();
		}

		@Test
		@DisplayName("PNG 은 실행 파일이 아니다")
		void png() {
			assertThat(inspector.detect(bytes(0x89, 0x50, 0x4E, 0x47))).isEmpty();
		}

		@Test
		@DisplayName("평문은 실행 파일이 아니다")
		void plainText() {
			assertThat(inspector.detect("hello world".getBytes())).isEmpty();
		}

		/**
		 * 시그니처는 <b>선두</b>에 있어야 한다. 파일 중간에 같은 바이트가 나오는 것은
		 * 우연이며, 그것으로 차단하면 정상 파일을 대량으로 막게 된다.
		 */
		@Test
		@DisplayName("선두가 아니면 탐지하지 않는다")
		void notAtStart() {
			assertThat(inspector.detect(bytes(0x00, 0x4D, 0x5A, 0x90))).isEmpty();
		}

		@Test
		@DisplayName("ELF 는 4바이트가 다 맞아야 한다 — 3바이트 일치는 아니다")
		void partialElf() {
			assertThat(inspector.detect(bytes(0x7F, 0x45, 0x4C))).isEmpty();
		}

		@Test
		@DisplayName("빈 배열과 null 은 판정하지 않는다")
		void emptyInput() {
			assertThat(inspector.detect(new byte[0])).isEmpty();
			assertThat(inspector.detect(null)).isEmpty();
		}

		@Test
		@DisplayName("1바이트는 어떤 시그니처도 완성하지 못한다")
		void singleByte() {
			assertThat(inspector.detect(bytes(0x4D))).isEmpty();
			assertThat(inspector.detect(bytes(0x23))).isEmpty();
		}
	}

	@Nested
	@DisplayName("이 검사의 한계 — 문서가 아니라 테스트로 고정한다")
	class Limits {

		/**
		 * <b>시그니처가 없는 평문 스크립트는 내용 검사로 잡을 수 없다.</b>
		 * 매직넘버를 늘려서 해결되는 문제가 아니며 확장자 목록으로만 막힌다.
		 * 과제가 고정 7개를 지정한 이유가 여기 있다.
		 */
		@Test
		@DisplayName("bat·cmd·js 내용은 탐지되지 않는다 — 확장자 목록으로만 막힌다")
		void plainScriptsAreInvisible() {
			assertThat(inspector.detect("@echo off\ndel /f /q C:\\*".getBytes())).isEmpty();
			assertThat(inspector.detect("require('fs').rmSync('/', {recursive:true})".getBytes()))
				.isEmpty();
		}

		/**
		 * 앞에 정상 시그니처를 붙이면 통과한다. 매직넘버는 방어선이 아니라 보조 수단이다.
		 * 다운로드 응답의 {@code attachment} + {@code nosniff} 가 함께 있어야 의미가 생긴다.
		 */
		@Test
		@DisplayName("가짜 헤더를 앞에 붙이면 통과한다")
		void headerSpoofingPasses() {
			byte[] disguised = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
				0x4D, 0x5A };

			assertThat(inspector.detect(disguised)).isEmpty();
		}
	}

	private static byte[] bytes(int... values) {
		byte[] result = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = (byte) values[i];
		}
		return result;
	}
}

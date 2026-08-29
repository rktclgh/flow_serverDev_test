package flow.test.serverdev.upload;

/**
 * 파일 선두에서 식별되는 실행 파일 형식. (SPEC §6)
 *
 * <p>어떤 시그니처에 걸렸는지 감사 로그에 남기기 위해 열거형으로 구분한다.
 * HTTP 응답에서는 모두 {@code FILE_EXECUTABLE_CONTENT} 로 합쳐진다 —
 * 공격자에게 탐지 세부를 알려줄 이유가 없다.
 */
public enum ExecutableSignature {

	/** {@code 4D 5A} = "MZ". Windows PE (exe/dll/scr). */
	WINDOWS_PE(0x4D, 0x5A),

	/** {@code 7F 45 4C 46} = ".ELF". Linux 실행 바이너리. */
	ELF(0x7F, 0x45, 0x4C, 0x46),

	/** {@code 23 21} = "#!". 인터프리터 지정으로 시작하는 스크립트. */
	SHEBANG(0x23, 0x21);

	private final byte[] magic;

	ExecutableSignature(int... magic) {
		this.magic = new byte[magic.length];
		for (int i = 0; i < magic.length; i++) {
			this.magic[i] = (byte) magic[i];
		}
	}

	/**
	 * 주어진 바이트의 <b>선두</b>가 이 시그니처인가.
	 *
	 * <p>선두로 제한하는 것이 중요하다. 파일 중간에 같은 바이트가 나오는 것은 우연이며,
	 * 그것으로 차단하면 정상 파일을 대량으로 막게 된다(오탐).
	 */
	boolean matches(byte[] prefix) {
		if (prefix.length < magic.length) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if (prefix[i] != magic[i]) {
				return false;
			}
		}
		return true;
	}

	/** 판정에 필요한 바이트 수. */
	int length() {
		return magic.length;
	}
}

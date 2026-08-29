package flow.test.serverdev.upload;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 파일 <b>내용</b>으로 실행 파일을 판정한다. (SPEC §6)
 *
 * <p>확장자는 이름일 뿐이라 {@code cp malware.exe report.jpg} 한 줄로 바꿀 수 있다.
 * 내용은 그렇게 바꿀 수 없다. 이름 검사(확장자)와 내용 검사(여기)는 서로 다른 것을 잡는다.
 *
 * <table>
 *   <caption>두 검사의 상호보완</caption>
 *   <tr><th>파일</th><th>이름 검사</th><th>내용 검사</th></tr>
 *   <tr><td>{@code virus.exe}</td><td>잡음</td><td>잡음</td></tr>
 *   <tr><td>{@code virus.jpg} (실제 exe)</td><td><b>놓침</b></td><td>잡음</td></tr>
 *   <tr><td>{@code evil.bat}, {@code evil.js}</td><td>잡음</td><td><b>놓침</b></td></tr>
 * </table>
 *
 * <p><b>한계를 분명히 해둔다.</b> 시그니처가 없는 평문 스크립트(bat/cmd/js/ps1/vbs/hta)는
 * 내용 검사로 잡을 수 없다. 매직넘버를 늘려서 해결되는 문제가 아니며 <b>확장자 목록으로만</b>
 * 막힌다 — 과제가 고정 7개를 지정한 이유가 여기 있다.
 * 앞에 가짜 시그니처를 붙이는 위장도 통과한다. 매직넘버는 방어선이 아니라 보조 수단이며,
 * 다운로드 응답의 {@code attachment} + {@code nosniff} 와 함께여야 의미가 있다.
 */
@Component
public class SignatureInspector {

	/**
	 * 읽어야 할 선두 바이트 수. 가장 긴 시그니처(ELF, 4바이트)에 맞춘다.
	 * <b>파일 크기와 무관하게 비용이 고정</b>이라는 것이 이 검사의 핵심 성질이다.
	 */
	public static final int PREFIX_LENGTH =
		Arrays.stream(ExecutableSignature.values()).mapToInt(ExecutableSignature::length).max().orElse(0);

	/**
	 * 선두 바이트로 실행 파일 형식을 판정한다.
	 *
	 * @param prefix 파일 선두 바이트. {@link #PREFIX_LENGTH} 보다 짧아도 되고 길어도 된다 —
	 *               짧으면 그 길이로 판정 가능한 시그니처만 대상이 되고, 길면 앞부분만 본다
	 * @return 매치된 시그니처. 없으면 empty
	 */
	public Optional<ExecutableSignature> detect(byte[] prefix) {
		if (prefix == null || prefix.length == 0) {
			return Optional.empty();
		}
		return Arrays.stream(ExecutableSignature.values())
			.filter(signature -> signature.matches(prefix))
			.findFirst();
	}
}

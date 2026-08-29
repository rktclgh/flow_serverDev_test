package flow.test.serverdev.upload;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.policy.BlockedExtensionRepository;
import flow.test.serverdev.policy.domain.BlockedExtension;

/**
 * 업로드 판정. (SPEC §21.4)
 *
 * <p><b>스토리지도 감사도 HTTP 도 모른다.</b> 판정은 저장 가능 여부와 무관하고, 그래야 판정만
 * 따로 검증할 수 있다. 이 클래스가 아는 것은 파일명·크기·선두 바이트·정책 넷뿐이다.
 */
@Component
public class UploadValidator {

	private final FilenameAnalyzer filenameAnalyzer;
	private final BlockedExtensionRepository repository;
	private final SignatureInspector signatureInspector;
	private final boolean allowExtensionless;

	public UploadValidator(FilenameAnalyzer filenameAnalyzer, BlockedExtensionRepository repository,
			SignatureInspector signatureInspector,
			@Value("${app.policy.allow-extensionless:false}") boolean allowExtensionless) {
		this.filenameAnalyzer = filenameAnalyzer;
		this.repository = repository;
		this.signatureInspector = signatureInspector;
		this.allowExtensionless = allowExtensionless;
	}

	/**
	 * @param rawFilename 클라이언트가 보낸 파일명. 신뢰하지 않는다
	 * @param size        바이트 수
	 * @param prefix      선두 바이트. {@link SignatureInspector#PREFIX_LENGTH} 보다 짧아도 된다
	 */
	public UploadDecision validate(String rawFilename, long size, byte[] prefix) {
		FilenameAnalysis analysis = filenameAnalyzer.analyze(rawFilename);
		if (analysis instanceof FilenameAnalysis.Rejected rejected) {
			return UploadDecision.Rejected.of(codeFor(rejected.reason()));
		}
		FilenameAnalysis.Ok ok = (FilenameAnalysis.Ok) analysis;

		// 내용이 없으면 판정할 것도 없다. 확장자 검사보다 앞선다.
		if (size == 0) {
			return UploadDecision.Rejected.of(ErrorCode.FILE_EMPTY);
		}

		if (ok.lastExtension().isEmpty() && !allowExtensionless) {
			return UploadDecision.Rejected.of(ErrorCode.FILE_EXTENSION_MISSING);
		}

		Optional<UploadDecision> blocked = ok.lastExtension().flatMap(this::rejectIfBlocked);
		if (blocked.isPresent()) {
			return blocked.get();
		}

		// 이름으로 걸러지지 않은 것만 내용을 본다. 파일 크기와 무관하게 선두 몇 바이트뿐이다.
		Optional<ExecutableSignature> signature = signatureInspector.detect(prefix);
		if (signature.isPresent()) {
			return new UploadDecision.Rejected(ErrorCode.FILE_EXECUTABLE_CONTENT,
				Map.of("signature", signature.get().name()));
		}

		return new UploadDecision.Accepted(ok.safeName(), ok.lastExtension(), ok.middleSegments());
	}

	/**
	 * 파일명 거부 5종을 두 코드로 접는다. <b>사용자가 대응할 수 있는 단위</b>로만 나눈다 —
	 * "널바이트가 들어있다" 는 정상 사용자에게 의미가 없고 공격자에게는 탐지 피드백이 된다.
	 * 길이만 분리하는 것은 그것이 유일하게 사용자가 고칠 수 있는 사유이기 때문이다.
	 */
	private static ErrorCode codeFor(FilenameRejectReason reason) {
		return reason == FilenameRejectReason.TOO_LONG
			? ErrorCode.FILE_NAME_TOO_LONG
			: ErrorCode.FILE_NAME_INVALID;
	}

	/**
	 * 목록에 있는 것과 차단된 것은 다르다. 고정 확장자 7개는 기본이 <b>체크 해제</b>이므로
	 * 행이 존재해도 {@code is_blocked} 가 거짓이면 통과시킨다.
	 */
	private Optional<UploadDecision> rejectIfBlocked(String extension) {
		return repository.findByName(extension)
			.filter(BlockedExtension::isBlocked)
			.map(found -> new UploadDecision.Rejected(ErrorCode.FILE_BLOCKED_EXTENSION,
				Map.of("blockedExtension", found.name(), "policyType", found.type().name())));
	}
}

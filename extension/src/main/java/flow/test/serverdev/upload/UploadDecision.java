package flow.test.serverdev.upload;

import java.util.Map;
import java.util.Optional;

import flow.test.serverdev.common.ErrorCode;

/**
 * 업로드 판정 결과. (SPEC §21.2)
 *
 * <p><b>거부를 예외가 아니라 값으로 표현한다.</b> 차단 확장자를 올린 것은 예외 상황이 아니라
 * 이 시스템이 정상적으로 내리는 판정이다. 예외로 만들면 정상 흐름을 예외로 제어하게 되고,
 * 호출자가 어떤 판정이 가능한지 타입으로 알 수 없다.
 */
public sealed interface UploadDecision {

	/**
	 * 저장해도 되는 파일.
	 *
	 * @param safeName       경로 구분자와 후행 점·공백을 제거한 basename
	 * @param extension      정규화된 마지막 확장자. 허용 설정이 켜져 있으면 비어 있을 수 있다
	 * @param note 관측 전용 <b>신호 이름</b>. 판정에 쓰지 않고 감사에만 남긴다.
	 *             값이 아니라 이름이므로 파일명 길이와 무관하게 컬럼 상한 안에 들어온다
	 */
	record Accepted(String safeName, Optional<String> extension, String note)
		implements UploadDecision {
	}

	/**
	 * 거부.
	 *
	 * @param code   응답 코드이자 <b>감사의 {@code reason_code}</b> 다. 화면에 보인 것과 기록이
	 *               어긋나지 않도록 같은 값을 쓴다
	 * @param detail 무엇이 왜 걸렸는지. SPEC §7.7 의 {@code detail} 로 그대로 나간다
	 */
	record Rejected(ErrorCode code, Map<String, Object> detail) implements UploadDecision {

		static Rejected of(ErrorCode code) {
			return new Rejected(code, Map.of());
		}
	}
}

package flow.test.serverdev.upload;

import java.util.List;
import java.util.Optional;

/**
 * 파일명 분석 결과. (SPEC §5)
 *
 * <p>{@code ExtensionNormalizer} 와 동일하게 실패를 예외가 아니라 값으로 표현한다.
 * 널바이트나 RTL 이 섞인 파일명은 예외적 상황이 아니라 공격자가 의도적으로 보낸 입력이며,
 * 호출부가 사유별로 다른 응답 코드를 내려야 한다.
 */
public sealed interface FilenameAnalysis {

	/**
	 * 분석 성공.
	 *
	 * @param safeName        경로 구분자와 후행 점/공백을 제거한 basename
	 * @param lastExtension   <b>차단 판정 대상</b>. 정규화된 마지막 확장자. 없으면 empty
	 * @param middleSegments  <b>관측 전용</b>. 차단 판정에 쓰지 않는다 — 감사 로그에만 기록된다
	 */
	record Ok(
		String safeName,
		Optional<String> lastExtension,
		List<String> middleSegments
	) implements FilenameAnalysis {}

	/** 파일명 자체가 부적합해 분석하지 않음. */
	record Rejected(FilenameRejectReason reason) implements FilenameAnalysis {}
}

package flow.test.serverdev.audit;

import java.net.InetAddress;

/**
 * 한 번의 업로드 시도에서 관측된 사실. 결말과 무관하게 동일하다.
 *
 * <p>결말({@link UploadResult})과 분리한 이유는 <b>기록 시점이 다르기 때문</b>이다.
 * 이 값들은 요청을 받은 순간 정해지지만 결말은 파이프라인 끝에서야 정해진다.
 *
 * @param originalFilename 원본 파일명. 제어문자는 저장 전에 이스케이프된다
 * @param clientIp         복원된 실제 클라이언트 주소. 얻지 못했으면 {@code null}
 * @param sizeBytes        파일 크기. 크기를 알기 전에 거부됐으면 {@code null}
 * @param matchedExtension 차단에 걸린 확장자(정규화된 형태). 해당 없으면 {@code null}
 * @param note             차단하지 않았으나 관측된 신호. 차단 정책과 관측을 분리한다
 */
public record UploadAttempt(
		String originalFilename,
		InetAddress clientIp,
		Long sizeBytes,
		String matchedExtension,
		String note) {
}

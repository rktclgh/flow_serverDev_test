package flow.test.serverdev.upload;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 파일 삭제.
 *
 * <p><b>인가는 {@code AdminTokenFilter} 가 맡는다.</b> 컬렉션 경로({@code /api/files})도
 * 보호 대상이다 — 필터의 {@code /api/files/}{@code **} 패턴이 세그먼트 0개도 매칭하기
 * 때문인데, 이것은 추론이 아니라 테스트로 고정돼 있다
 * ({@code DeletesMany#requiresAdminToken}). 벌크 경로가 조용히 공개되는 일이 없어야 한다.
 */
@RestController
@RequestMapping("/api/files")
public class FileDeleteController {

	private final FileDeleteService service;

	public FileDeleteController(FileDeleteService service) {
		this.service = service;
	}

	/**
	 * 단건 삭제. 지웠으면 {@code 204}, 없으면 {@code 404} 다.
	 *
	 * <p>벌크가 생긴 뒤에도 남겨둔다. 대상을 URL 로 지목하는 요청이라 "없으면 실패" 라는
	 * 답이 자연스럽고, 이미 문서와 화면이 쓰고 있다.
	 */
	@DeleteMapping("/{fileId}")
	public ResponseEntity<Void> delete(@PathVariable UUID fileId) {
		service.delete(fileId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * 여러 건 삭제. 건별 결과를 {@code 200} 으로 답한다.
	 *
	 * <p><b>본문 있는 {@code DELETE}</b> 다. RFC 9110 은 이 조합에 정의된 의미가 없다고만
	 * 하고 금지하지는 않는다. 지우는 행위를 {@code POST} 로 표현하면 로그와 감사에서
	 * 파괴적 요청이 눈에 덜 띄고, 질의 문자열로 나르면 삭제 대상이 액세스 로그에 그대로 남는다.
	 *
	 * <p>전부 성공 아니면 전부 실패로 답하지 않는 이유는 {@link BulkDeleteResponse} 에 있다.
	 */
	@DeleteMapping
	public BulkDeleteResponse deleteMany(@Valid @RequestBody BulkDeleteRequest request) {
		return service.deleteAll(request.fileIds());
	}
}

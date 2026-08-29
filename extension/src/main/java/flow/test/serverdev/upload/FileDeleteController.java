package flow.test.serverdev.upload;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업로드된 파일 삭제. (DELETE /api/files/&#123;fileId&#125;)
 *
 * <p><b>관리 토큰이 필요한 유일한 파일 API 다.</b> 업로드·목록·다운로드는 공개지만 삭제는
 * 파괴적이다. 공개로 두면 누구나 남의 파일을 지울 수 있고, 그러면 목록도 다운로드도 의미가
 * 없어진다. 정책 변경과 같은 등급으로 다룬다 — 인가는 {@code AdminTokenFilter} 가 한다.
 *
 * <p>본문 없이 {@code 204} 를 준다. 삭제된 것을 다시 실어 보낼 이유가 없고, 실어 보내면
 * "지웠는데 내용이 돌아온다" 는 이상한 계약이 된다.
 */
@RestController
@RequestMapping("/api/files")
public class FileDeleteController {

	private final FileDeleteService service;

	public FileDeleteController(FileDeleteService service) {
		this.service = service;
	}

	@DeleteMapping("/{fileId}")
	public ResponseEntity<Void> delete(@PathVariable UUID fileId) {
		service.delete(fileId);
		return ResponseEntity.noContent().build();
	}
}

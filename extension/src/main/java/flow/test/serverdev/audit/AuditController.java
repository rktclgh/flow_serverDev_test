package flow.test.serverdev.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 활동 로그. (GET /api/audit)
 *
 * <p><b>읽기인데도 관리 토큰을 요구한다.</b> 이 서비스에서 유일한 예외다. 다른 조회는
 * 화면을 그리는 데 필요해 열어 두었지만, 이 응답에는 다른 사람이 올린 파일명과 요청자
 * 주소, 차단 사유가 한데 담긴다. <b>상태를 바꾸지 않는 것과 보여줘도 되는 것은 다르다.</b>
 *
 * <p>토큰을 요구하는 기준은 둘이다 — 되돌릴 수 없는가, 그리고 응답 자체가 관리 정보인가.
 * 뒤쪽에 걸리는 것은 지금 이 경로뿐이다.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

	private final AuditQueryService service;

	public AuditController(AuditQueryService service) {
		this.service = service;
	}

	@GetMapping
	public AuditResponse recent(@RequestParam(required = false) Integer limit) {
		return new AuditResponse(service.recent(limit));
	}

	public record AuditResponse(List<AuditEntry> entries) {
	}
}

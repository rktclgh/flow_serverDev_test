package flow.test.serverdev.policy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import flow.test.serverdev.policy.dto.CustomCreateRequest;
import flow.test.serverdev.policy.dto.FixedToggleRequest;
import flow.test.serverdev.policy.dto.PolicyResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 확장자 차단 정책 API. (SPEC §7.1~7.4)
 *
 * <p><b>조회는 공개, 변경은 관리 토큰</b>이다. 과제의 "누구나 접속 가능" 요구는 조회·업로드에
 * 걸리고, 변경까지 열어두면 누구나 {@code exe} 차단을 풀 수 있어 정책 자체가 무의미해진다.
 * 인가는 {@code AdminTokenFilter} 가 담당하므로 여기에는 인증 코드가 없다.
 *
 * <p>컨트롤러에 try-catch 가 없는 것도 의도다. 실패 응답의 형태는
 * {@code GlobalExceptionHandler} 한 곳에서만 만들어진다.
 */
@RestController
@RequestMapping("/api/extensions")
public class PolicyController {

	private final PolicyService policyService;
	private final PolicyAuditLogger auditLogger;

	public PolicyController(PolicyService policyService, PolicyAuditLogger auditLogger) {
		this.policyService = policyService;
		this.auditLogger = auditLogger;
	}

	@GetMapping
	public PolicyResponse getPolicy() {
		return policyService.getPolicy();
	}

	@PatchMapping("/fixed/{name}")
	public PolicyResponse.FixedItem toggleFixed(@PathVariable String name,
			@Valid @RequestBody FixedToggleRequest request, HttpServletRequest http) {

		PolicyResponse.FixedItem item = policyService.toggleFixed(name, request.blocked());
		auditLogger.changed(item.blocked() ? "FIXED_BLOCK" : "FIXED_UNBLOCK", item.name(), http);
		return item;
	}

	/**
	 * 정규화된 값을 응답에 담는다. 클라이언트가 <b>입력한 것과 저장된 것이 다른지</b>
	 * 비교해 사용자에게 알릴 수 있도록 하기 위함이다(SPEC §20).
	 * 프론트가 정규화를 흉내 내면 규칙이 세 곳에 생기므로, 결과를 내려주는 쪽을 택했다.
	 */
	@PostMapping("/custom")
	@ResponseStatus(HttpStatus.CREATED)
	public PolicyResponse.CustomItem addCustom(@Valid @RequestBody CustomCreateRequest request,
			HttpServletRequest http) {

		PolicyResponse.CustomItem item = policyService.addCustom(request.name());
		auditLogger.changed("CUSTOM_ADD", item.name(), http);
		return item;
	}

	@DeleteMapping("/custom/{name}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteCustom(@PathVariable String name, HttpServletRequest http) {
		policyService.deleteCustom(name);
		auditLogger.changed("CUSTOM_DELETE", name, http);
	}
}

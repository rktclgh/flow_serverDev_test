package flow.test.serverdev.common;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배포 확인용 헬스 엔드포인트.
 *
 * <p>Actuator를 쓰지 않는다. nginx에서 /actuator 를 통째로 404 처리하기 때문에
 * 별도 엔드포인트를 두는 편이 단순하고, 노출 표면도 줄어든다.
 */
@RestController
public class HealthController {

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}
}

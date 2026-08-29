package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import flow.test.serverdev.support.IntegrationTest;

/**
 * 스위퍼 배선.
 *
 * <p><b>구현이 있는 것과 실제로 등록되어 도는 것은 다르다.</b> 이 스위퍼는 구현과 단위 테스트가
 * 모두 끝난 뒤에도 한동안 빈으로 등록되지 않은 채였고, 그동안 테스트는 290개가 전부 초록이었다.
 * 조건부 등록이 어긋나면 예외가 아니라 <b>조용한 미등록</b>이고, 스위퍼가 안 도는 것은
 * 고아 객체가 쌓여야 드러난다. 그때는 이미 늦다.
 *
 * <p>그래서 동작이 아니라 <b>존재</b>를 검사하는 테스트를 따로 둔다.
 */
@DisplayName("스위퍼 배선")
class PendingUploadSweeperWiringTest extends IntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("PENDING 스위퍼가 빈으로 등록된다")
	void sweeperIsRegistered() {
		assertThat(context.getBeanNamesForType(PendingUploadSweeper.class))
			.as("등록되지 않으면 고아 객체가 영원히 남는다")
			.hasSize(1);
	}
}

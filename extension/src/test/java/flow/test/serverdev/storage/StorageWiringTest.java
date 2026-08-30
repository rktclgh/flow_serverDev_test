package flow.test.serverdev.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import flow.test.serverdev.support.IntegrationTest;

/**
 * 스토리지 배선. (SPEC §17 P4)
 *
 * <p>구현이 있는 것과 <b>실제로 배선되어 도는 것</b>은 다르다. 스위퍼는 구현이 끝난 뒤에도
 * {@code ObjectStorage} 빈이 없어 등록조차 되지 않는 상태로 한동안 있었고, 테스트는 전부
 * 초록이었다. 조용히 죽어 있는 기능을 만들지 않기 위해 등록 자체를 검사한다.
 */
@DisplayName("스토리지 배선")
class StorageWiringTest extends IntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("ObjectStorage 가 빈으로 등록된다")
	void objectStorageBean() {
		assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(MinioObjectStorage.class);
	}

	@Test
	@DisplayName("저장 키 생성기와 Clock 이 등록된다")
	void keyGeneratorBean() {
		assertThat(context.getBean(StorageKeyGenerator.class)).isNotNull();
		assertThat(context.getBean(Clock.class)).isNotNull();
	}
}

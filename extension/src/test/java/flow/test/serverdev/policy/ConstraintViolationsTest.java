package flow.test.serverdev.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.policy.domain.BlockedExtension;
import flow.test.serverdev.support.IntegrationTest;

/**
 * 제약 위반 판별식을 <b>실제 위반 예외</b>로 검증한다.
 *
 * <p>문자열을 지어내서 넣지 않는다. 그러면 Postgres 가 실제로 보내는 메시지가 아니라
 * <b>내가 그럴 것이라고 상상한 메시지</b>를 검증하게 된다. 여기서는 리포지토리로 진짜 위반을
 * 일으켜 그 예외를 그대로 판별식에 넣는다.
 *
 * <p>이 판별식은 {@code PolicyService} 안에서 advisory lock 뒤에 있어 정상 흐름에서는
 * 거의 도달하지 않는다. 즉 <b>틀려 있어도 다른 테스트는 모두 통과한다</b>. 그래서 직접 겨눈다.
 */
@DisplayName("제약 위반 판별")
class ConstraintViolationsTest extends IntegrationTest {

	private static final String NAME_CONSTRAINT = "uq_blocked_extension_name";
	private static final String SLOT_CONSTRAINT = "uq_blocked_extension_slot";

	@Autowired
	BlockedExtensionRepository repository;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		jdbc.update("DELETE FROM blocked_extension WHERE type = 'CUSTOM'");
	}

	@Test
	@DisplayName("이름 중복은 이름 제약으로 식별된다 — 슬롯 제약과 혼동하지 않는다")
	void nameConflictIsIdentified() {
		repository.saveAndFlush(BlockedExtension.custom("sh", (short) 1));

		DataIntegrityViolationException violation = catchThrowableOfType(
			() -> repository.saveAndFlush(BlockedExtension.custom("sh", (short) 2)),
			DataIntegrityViolationException.class);

		assertThat(ConstraintViolations.involves(violation, NAME_CONSTRAINT)).isTrue();
		assertThat(ConstraintViolations.involves(violation, SLOT_CONSTRAINT)).isFalse();
	}

	@Test
	@DisplayName("슬롯 중복은 슬롯 제약으로 식별된다 — 이 구분이 재시도 여부를 가른다")
	void slotConflictIsIdentified() {
		repository.saveAndFlush(BlockedExtension.custom("aa", (short) 1));

		DataIntegrityViolationException violation = catchThrowableOfType(
			() -> repository.saveAndFlush(BlockedExtension.custom("bb", (short) 1)),
			DataIntegrityViolationException.class);

		assertThat(ConstraintViolations.involves(violation, SLOT_CONSTRAINT)).isTrue();
		assertThat(ConstraintViolations.involves(violation, NAME_CONSTRAINT)).isFalse();
	}

	/**
	 * 정규화를 통과하지 못한 값이 어떤 경로로든 저장되려 하면 형식 CHECK 가 막는다.
	 * 이 위반은 이름 제약도 슬롯 제약도 아니므로 <b>재시도 대상이 아니다</b> —
	 * 재시도하면 같은 실패를 세 번 반복하고 엉뚱한 코드를 내보낸다.
	 */
	@Test
	@DisplayName("형식 CHECK 위반은 이름·슬롯 어느 쪽도 아니다")
	void formatViolationIsNeither() {
		DataIntegrityViolationException violation = catchThrowableOfType(
			() -> jdbc.update("INSERT INTO blocked_extension (name, type, is_blocked, custom_slot) "
				+ "VALUES ('BAD', 'CUSTOM', TRUE, 5)"),
			DataIntegrityViolationException.class);

		assertThat(ConstraintViolations.involves(violation, NAME_CONSTRAINT)).isFalse();
		assertThat(ConstraintViolations.involves(violation, SLOT_CONSTRAINT)).isFalse();
	}
}

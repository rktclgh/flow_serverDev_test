package flow.test.serverdev.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.common.PolicyException;
import flow.test.serverdev.policy.dto.PolicyResponse;
import flow.test.serverdev.support.IntegrationTest;
import flow.test.serverdev.support.PolicyFixture;

/**
 * 정책 서비스의 도메인 규칙 검증. (SPEC §7.1~7.4, §8.1)
 *
 * <p>HTTP 계층은 {@code PolicyControllerTest} 가 담당한다. 여기서는 상태 코드가 아니라
 * <b>어떤 규칙이 어떤 이유로 거부하는가</b>를 확인한다.
 */
@DisplayName("정책 서비스")
class PolicyServiceTest extends IntegrationTest {

	private static final List<String> FIXED_NAMES = List.of("bat", "cmd", "com", "cpl", "exe", "scr", "js");

	@Autowired
	PolicyService service;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		PolicyFixture.reset(jdbc);
	}

	@Nested
	@DisplayName("조회")
	class Query {

		@Test
		@DisplayName("고정 7개는 요구사항 순서로 반환된다")
		void fixedOrderIsStable() {
			PolicyResponse response = service.getPolicy();

			assertThat(response.fixed()).extracting(PolicyResponse.FixedItem::name)
				.containsExactlyElementsOf(FIXED_NAMES);
		}

		@Test
		@DisplayName("초기 상태는 고정 전부 미차단, 커스텀 0개")
		void defaultsAreUnchecked() {
			PolicyResponse response = service.getPolicy();

			assertThat(response.fixed()).extracting(PolicyResponse.FixedItem::blocked)
				.containsOnly(false);
			assertThat(response.custom()).isEmpty();
			assertThat(response.customCount()).isZero();
			assertThat(response.customLimit()).isEqualTo(200);
		}

		@Test
		@DisplayName("커스텀은 이름 오름차순으로 반환된다")
		void customIsSortedByName() {
			service.addCustom("zip");
			service.addCustom("apk");
			service.addCustom("msi");

			assertThat(service.getPolicy().custom()).extracting(PolicyResponse.CustomItem::name)
				.containsExactly("apk", "msi", "zip");
		}
	}

	@Nested
	@DisplayName("고정 확장자 토글")
	class FixedToggle {

		@Test
		@DisplayName("차단하면 조회에 반영된다")
		void togglePersists() {
			service.toggleFixed("exe", true);

			assertThat(service.getPolicy().fixed())
				.filteredOn(item -> item.name().equals("exe"))
				.extracting(PolicyResponse.FixedItem::blocked)
				.containsExactly(true);
		}

		@Test
		@DisplayName("같은 값을 다시 보내도 성공한다 — 멱등")
		void toggleIsIdempotent() {
			service.toggleFixed("exe", true);

			assertThat(service.toggleFixed("exe", true).blocked()).isTrue();
		}

		@Test
		@DisplayName("해제도 반영된다")
		void toggleOff() {
			service.toggleFixed("js", true);

			assertThat(service.toggleFixed("js", false).blocked()).isFalse();
		}

		@Test
		@DisplayName("경로 변수도 정규화한다 — \".EXE \" 는 exe 로 처리된다")
		void pathVariableIsNormalized() {
			assertThat(service.toggleFixed(".EXE ", true).name()).isEqualTo("exe");
		}

		@Test
		@DisplayName("고정 7개가 아니면 EXT_NOT_FOUND")
		void unknownFixedName() {
			assertThatThrownBy(() -> service.toggleFixed("sh", true))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_NOT_FOUND);
		}

		@Test
		@DisplayName("커스텀 확장자를 고정 경로로 토글하면 EXT_NOT_FOUND")
		void customCannotBeToggledAsFixed() {
			service.addCustom("sh");

			assertThatThrownBy(() -> service.toggleFixed("sh", true))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_NOT_FOUND);
		}

		@Test
		@DisplayName("정규화 불가능한 이름은 EXT_NOT_FOUND — 그런 리소스가 존재할 수 없다")
		void unnormalizableNameIsNotFound() {
			assertThatThrownBy(() -> service.toggleFixed("e!e", true))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_NOT_FOUND);
		}
	}

	@Nested
	@DisplayName("커스텀 추가")
	class CustomAdd {

		@Test
		@DisplayName("정규화된 값으로 저장하고 그 값을 반환한다")
		void normalizesBeforeSaving() {
			assertThat(service.addCustom(".SH ").name()).isEqualTo("sh");
			assertThat(service.getPolicy().custom()).extracting(PolicyResponse.CustomItem::name)
				.containsExactly("sh");
		}

		@Test
		@DisplayName("커스텀은 행의 존재 자체가 차단이다 — is_blocked 는 항상 true")
		void customIsAlwaysBlocked() {
			service.addCustom("sh");

			Boolean blocked = jdbc.queryForObject(
				"SELECT is_blocked FROM blocked_extension WHERE name = 'sh'", Boolean.class);
			assertThat(blocked).isTrue();
		}

		@Test
		@DisplayName("슬롯은 1번부터 할당된다")
		void slotStartsFromOne() {
			service.addCustom("sh");

			Short slot = jdbc.queryForObject(
				"SELECT custom_slot FROM blocked_extension WHERE name = 'sh'", Short.class);
			assertThat(slot).isEqualTo((short) 1);
		}

		@Test
		@DisplayName("삭제된 슬롯은 재사용된다")
		void freedSlotIsReused() {
			service.addCustom("aa");
			service.addCustom("bb");
			service.deleteCustom("aa");
			service.addCustom("cc");

			Short slot = jdbc.queryForObject(
				"SELECT custom_slot FROM blocked_extension WHERE name = 'cc'", Short.class);
			assertThat(slot).isEqualTo((short) 1);
		}

		@Test
		@DisplayName("이미 있는 커스텀이면 EXT_DUPLICATE")
		void duplicateCustom() {
			service.addCustom("sh");

			assertThatThrownBy(() -> service.addCustom("sh"))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_DUPLICATE);
		}

		@Test
		@DisplayName("정규화 결과가 고정과 겹치면 EXT_FIXED_CONFLICT — 어디서 처리할지 안내한다")
		void fixedConflict() {
			assertThatThrownBy(() -> service.addCustom(".EXE"))
				.isInstanceOf(PolicyException.class)
				.satisfies(e -> {
					PolicyException ex = (PolicyException) e;
					assertThat(ex.errorCode()).isEqualTo(ErrorCode.EXT_FIXED_CONFLICT);
					assertThat(ex.getMessage()).contains("고정 확장자");
				});
		}

		@Test
		@DisplayName("정규화 실패는 EXT_INVALID_FORMAT")
		void invalidFormat() {
			assertThatThrownBy(() -> service.addCustom("s h"))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_INVALID_FORMAT);
		}

		@Test
		@DisplayName("21자는 EXT_TOO_LONG — 형식 오류와 구분해서 알려준다")
		void tooLong() {
			assertThatThrownBy(() -> service.addCustom("a".repeat(21)))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_TOO_LONG);
		}

		@Test
		@DisplayName("20자는 허용된다 — 경계값")
		void exactlyTwentyIsAllowed() {
			assertThat(service.addCustom("a".repeat(20)).name()).hasSize(20);
		}

		@Test
		@DisplayName("빈 슬롯이 없으면 EXT_LIMIT_EXCEEDED")
		void limitExceeded() {
			PolicyFixture.fillCustomSlots(jdbc, 200);

			assertThatThrownBy(() -> service.addCustom("zzz"))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_LIMIT_EXCEEDED);
		}

		@Test
		@DisplayName("200번째는 성공한다 — 경계값")
		void twoHundredthSucceeds() {
			PolicyFixture.fillCustomSlots(jdbc, 199);

			assertThat(service.addCustom("zzz").name()).isEqualTo("zzz");
			assertThat(service.getPolicy().customCount()).isEqualTo(200);
		}
	}

	@Nested
	@DisplayName("커스텀 삭제")
	class CustomDelete {

		@Test
		@DisplayName("삭제하면 목록에서 사라진다")
		void deleteRemoves() {
			service.addCustom("sh");
			service.deleteCustom("sh");

			assertThat(service.getPolicy().custom()).isEmpty();
		}

		@Test
		@DisplayName("삭제도 정규화를 거친다")
		void deleteNormalizes() {
			service.addCustom("sh");
			service.deleteCustom(".SH ");

			assertThat(service.getPolicy().custom()).isEmpty();
		}

		/** 호출자가 감사 기록에 <b>실제로 지워진 값</b>을 남길 수 있어야 한다. */
		@Test
		@DisplayName("삭제는 실제로 지워진 정규화된 이름을 돌려준다")
		void deleteReturnsNormalizedName() {
			service.addCustom("sh");

			assertThat(service.deleteCustom(".SH ").name()).isEqualTo("sh");
		}

		@Test
		@DisplayName("없는 이름이면 EXT_NOT_FOUND")
		void deleteMissing() {
			assertThatThrownBy(() -> service.deleteCustom("sh"))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_NOT_FOUND);
		}

		@Test
		@DisplayName("고정 확장자는 EXT_FIXED_NOT_DELETABLE")
		void fixedIsNotDeletable() {
			assertThatThrownBy(() -> service.deleteCustom("exe"))
				.isInstanceOf(PolicyException.class)
				.extracting(e -> ((PolicyException) e).errorCode())
				.isEqualTo(ErrorCode.EXT_FIXED_NOT_DELETABLE);
		}
	}
}

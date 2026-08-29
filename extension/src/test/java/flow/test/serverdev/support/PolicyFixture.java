package flow.test.serverdev.support;

import java.util.stream.IntStream;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 정책 테스트가 공유하는 준비 작업. SQL 이 한 곳에만 있도록 모았다.
 *
 * <p><b>{@code IntegrationTest} 기반 클래스에 두지 않은 것은 의도다.</b>
 * 그 클래스는 스프링 컨텍스트가 필요한 <i>모든</i> 테스트의 기반이다. 정책 SQL 을 거기에 넣으면
 * 앞으로 추가될 업로드·스토리지 테스트가 자기와 무관한 초기화를 상속받는다.
 *
 * <p>더 큰 문제는 <b>상속된 {@code @BeforeEach} 가 보이지 않는 곳에서 DB 를 비운다</b>는 것이다.
 * 픽스처를 선언하지 않은 테스트 클래스도 데이터가 지워진다. 테스트가 무엇을 전제하는지
 * 그 파일만 봐서는 알 수 없게 되고, 이는 테스트를 읽기 어렵게 만드는 종류의 결합이다.
 *
 * <p>정적 메서드로 두면 중복은 사라지면서 각 테스트가 <b>자기 전제를 한 줄로 선언</b>한다.
 */
public final class PolicyFixture {

	private PolicyFixture() {
	}

	/**
	 * 커스텀은 모두 지우고 고정은 미차단으로 되돌린다.
	 *
	 * <p>고정 7행을 지우지 않는 이유는 트리거가 막기 때문이 아니라, 지워서는 안 되기 때문이다.
	 * 그 7행은 마이그레이션 시드가 유일한 출처다.
	 */
	public static void reset(JdbcTemplate jdbc) {
		jdbc.update("DELETE FROM blocked_extension WHERE type = 'CUSTOM'");
		jdbc.update("UPDATE blocked_extension SET is_blocked = FALSE WHERE type = 'FIXED'");
	}

	/**
	 * 슬롯 1번부터 {@code count} 개를 채운다. 상한 경계를 시험할 때 쓴다.
	 * 서비스를 거치면 느려서 JDBC 로 직접 넣는다.
	 */
	public static void fillCustomSlots(JdbcTemplate jdbc, int count) {
		jdbc.batchUpdate(
			"INSERT INTO blocked_extension (name, type, is_blocked, custom_slot) VALUES (?, 'CUSTOM', TRUE, ?)",
			IntStream.rangeClosed(1, count)
				.mapToObj(i -> new Object[] { "c%03d".formatted(i), (short) i })
				.toList());
	}
}

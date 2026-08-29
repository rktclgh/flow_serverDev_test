package flow.test.serverdev.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.common.PolicyException;
import flow.test.serverdev.support.IntegrationTest;

/**
 * 동시성과 멱등. (SPEC §8.1)
 *
 * <p><b>"동시성에서 옳다"는 말이 연산마다 정반대를 뜻한다.</b>
 *
 * <ul>
 *   <li>커스텀 추가는 <b>비멱등</b>이다 — 같은 요청이 여럿 와도 <b>정확히 하나만</b> 성공해야 한다
 *   <li>고정 토글은 <b>멱등</b>이다 — 같은 요청이 여럿 오면 <b>전부</b> 성공해야 한다
 * </ul>
 *
 * <p>그래서 두 규칙을 각각 검증하는 것으로는 부족하다. 한쪽 규칙이 다른 쪽으로 새면
 * (예: 토글에 중복 방지가 걸리거나, 추가가 마지막 요청으로 덮어써지면)
 * 개별 테스트는 통과하면서 서로 모순되는 동작을 하게 된다.
 * 마지막 {@code 둘을함께} 가 그 지점을 본다.
 *
 * <p><b>서비스 계층에서 검증하는 이유</b>: MockMvc 는 단일 스레드 사용을 전제로 만들어졌다.
 * 그리고 검증하려는 불변식이 사는 곳은 HTTP 가 아니라 <b>트랜잭션과 DB 제약</b>이다.
 */
@DisplayName("동시성과 멱등")
class PolicyConcurrencyTest extends IntegrationTest {

	/** Hikari 기본 풀(10)보다 작게 잡는다. 커넥션 대기 때문에 테스트가 느려지지 않도록. */
	private static final int THREADS = 8;

	@Autowired
	PolicyService service;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		jdbc.update("DELETE FROM blocked_extension WHERE type = 'CUSTOM'");
		jdbc.update("UPDATE blocked_extension SET is_blocked = FALSE WHERE type = 'FIXED'");
	}

	@Nested
	@DisplayName("비멱등 — 정확히 하나만 성공한다")
	class NonIdempotent {

		@Test
		@DisplayName("같은 이름을 동시에 추가하면 1개만 201, 나머지는 EXT_DUPLICATE")
		void sameNameAddedConcurrently() throws Exception {
			List<Optional<ErrorCode>> results =
				runConcurrently(Collections.nCopies(THREADS, () -> service.addCustom("sh")));

			assertThat(successCount(results)).isEqualTo(1);
			assertThat(failures(results)).containsOnly(ErrorCode.EXT_DUPLICATE);
			assertThat(customRowCount()).isEqualTo(1);
		}

		/**
		 * 앱의 {@code count()} 체크만으로는 두 요청이 동시에 199개를 보고 <b>둘 다 통과</b>한다.
		 * 상한을 실제로 보증하는 것은 {@code custom_slot} 의 CHECK + UNIQUE 다(SPEC §3.1).
		 */
		@Test
		@DisplayName("빈 슬롯이 1개일 때 서로 다른 이름을 동시에 추가하면 1개만 성공한다")
		void lastSlotIsContended() throws Exception {
			fillCustomSlots(199);

			List<Runnable> attempts = IntStream.range(0, THREADS)
				.<Runnable>mapToObj(i -> () -> service.addCustom("race" + i))
				.toList();

			List<Optional<ErrorCode>> results = runConcurrently(attempts);

			assertThat(successCount(results)).isEqualTo(1);
			assertThat(failures(results)).containsOnly(ErrorCode.EXT_LIMIT_EXCEEDED);
			assertThat(customRowCount()).isEqualTo(200);
		}

		@Test
		@DisplayName("동시 삭제는 1개만 204, 나머지는 404 — 응답은 다르지만 결과 상태는 같다")
		void sameNameDeletedConcurrently() throws Exception {
			service.addCustom("sh");

			List<Optional<ErrorCode>> results =
				runConcurrently(Collections.nCopies(THREADS, () -> service.deleteCustom("sh")));

			assertThat(successCount(results)).isEqualTo(1);
			assertThat(failures(results)).containsOnly(ErrorCode.EXT_NOT_FOUND);
			// DELETE 는 응답이 멱등하지 않지만 효과는 멱등하다 — 몇 번을 보내도 결과는 "없음"이다.
			assertThat(customRowCount()).isZero();
		}
	}

	@Nested
	@DisplayName("멱등 — 전부 성공하고 상태가 같다")
	class Idempotent {

		@Test
		@DisplayName("같은 값으로 반복 토글해도 매번 성공하고 상태가 유지된다")
		void repeatedToggleIsStable() {
			for (int i = 0; i < 5; i++) {
				assertThat(service.toggleFixed("exe", true).blocked()).isTrue();
			}

			assertThat(isBlocked("exe")).isTrue();
		}

		@Test
		@DisplayName("같은 값을 동시에 토글하면 전부 성공한다 — 하나만 통과시키면 안 된다")
		void sameValueToggledConcurrently() throws Exception {
			List<Optional<ErrorCode>> results =
				runConcurrently(Collections.nCopies(THREADS, () -> service.toggleFixed("exe", true)));

			assertThat(successCount(results)).isEqualTo(THREADS);
			assertThat(isBlocked("exe")).isTrue();
		}

		/**
		 * 서로 다른 값을 동시에 보내면 마지막 요청이 이긴다(last-write-wins, SPEC §8.1).
		 * 여기서 보증하는 것은 "어느 값이 이기는가"가 아니라
		 * <b>중간 상태로 찢어지거나 행이 늘어나지 않는다</b>는 것이다.
		 */
		@Test
		@DisplayName("엇갈린 값을 동시에 토글해도 상태는 둘 중 하나이고 행 수는 그대로다")
		void conflictingTogglesDoNotCorrupt() throws Exception {
			List<Runnable> attempts = IntStream.range(0, THREADS)
				.<Runnable>mapToObj(i -> {
					boolean blocked = i % 2 == 0;
					return () -> service.toggleFixed("exe", blocked);
				})
				.toList();

			List<Optional<ErrorCode>> results = runConcurrently(attempts);

			assertThat(successCount(results)).isEqualTo(THREADS);
			assertThat(isBlocked("exe")).isIn(true, false);
			assertThat(fixedRowCount()).isEqualTo(7);
		}
	}

	@Nested
	@DisplayName("둘을 함께 — 반대인 두 규칙이 같은 순간에 성립한다")
	class BothAtOnce {

		@Test
		@DisplayName("추가는 1개만, 토글은 전부 — 동시에 던져도 각자의 규칙을 지킨다")
		void oppositeRulesHoldSimultaneously() throws Exception {
			List<Runnable> attempts = Stream.concat(
					// 비멱등: 정확히 하나만 성공해야 한다
					IntStream.range(0, 4).<Runnable>mapToObj(i -> () -> service.addCustom("sh")),
					// 멱등: 전부 성공해야 한다
					IntStream.range(0, 4).<Runnable>mapToObj(i -> () -> service.toggleFixed("exe", true)))
				.toList();

			List<Optional<ErrorCode>> results = runConcurrently(attempts);

			// 앞 4개가 추가, 뒤 4개가 토글이다.
			List<Optional<ErrorCode>> adds = results.subList(0, 4);
			List<Optional<ErrorCode>> toggles = results.subList(4, 8);

			assertThat(successCount(adds)).as("비멱등 생성은 하나만").isEqualTo(1);
			assertThat(failures(adds)).containsOnly(ErrorCode.EXT_DUPLICATE);
			assertThat(successCount(toggles)).as("멱등 토글은 전부").isEqualTo(4);

			assertThat(customRowCount()).isEqualTo(1);
			assertThat(isBlocked("exe")).isTrue();
		}

		/**
		 * 추가·삭제·토글을 뒤섞어 돌린 뒤 <b>슬롯 불변식</b>이 남아 있는지 본다.
		 * 개별 응답이 무엇이었는지는 보지 않는다 — 경합 결과는 매 실행 달라도 되지만
		 * 불변식은 어떤 순서로 실행되든 깨지면 안 된다.
		 */
		@Test
		@DisplayName("추가·삭제·토글을 뒤섞어 돌려도 슬롯 불변식이 유지된다")
		void slotInvariantsSurviveChurn() throws Exception {
			fillCustomSlots(50);

			List<Runnable> attempts = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				String name = "churn" + i;
				attempts.add(() -> service.addCustom(name));
				attempts.add(() -> service.deleteCustom(name));
			}
			attempts.add(() -> service.toggleFixed("exe", true));
			attempts.add(() -> service.toggleFixed("js", true));

			runConcurrently(attempts);

			Integer rows = jdbc.queryForObject(
				"SELECT count(*) FROM blocked_extension WHERE type = 'CUSTOM'", Integer.class);
			Integer distinctSlots = jdbc.queryForObject(
				"SELECT count(DISTINCT custom_slot) FROM blocked_extension WHERE type = 'CUSTOM'",
				Integer.class);
			Integer outOfRange = jdbc.queryForObject("""
				SELECT count(*) FROM blocked_extension
				WHERE type = 'CUSTOM' AND (custom_slot IS NULL OR custom_slot NOT BETWEEN 1 AND 200)
				""", Integer.class);

			assertThat(distinctSlots).as("슬롯이 중복되지 않는다").isEqualTo(rows);
			assertThat(outOfRange).as("슬롯이 범위를 벗어나지 않는다").isZero();
			assertThat(rows).as("상한을 넘지 않는다").isLessThanOrEqualTo(200);
			assertThat(fixedRowCount()).as("고정 7행은 그대로다").isEqualTo(7);
		}
	}

	// --- 도구 ---------------------------------------------------------------

	/**
	 * 모든 스레드를 <b>같은 순간에</b> 출발시킨다. 단순히 submit 만 하면 앞선 작업이
	 * 끝난 뒤에 다음이 시작되어 경합이 일어나지 않고, 테스트가 통과해도 아무것도 증명하지 못한다.
	 *
	 * @return 각 작업의 결과. {@code Optional.empty()} 가 성공, 값이 있으면 그 사유로 거부됨
	 */
	private List<Optional<ErrorCode>> runConcurrently(List<Runnable> actions) throws Exception {
		int size = actions.size();
		ExecutorService pool = Executors.newFixedThreadPool(size);
		CountDownLatch ready = new CountDownLatch(size);
		CountDownLatch start = new CountDownLatch(1);

		try {
			List<Future<Optional<ErrorCode>>> futures = new ArrayList<>();
			for (Runnable action : actions) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					try {
						action.run();
						return Optional.<ErrorCode>empty();
					}
					catch (PolicyException exception) {
						return Optional.of(exception.errorCode());
					}
				}));
			}

			assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드가 준비됨").isTrue();
			start.countDown();

			List<Optional<ErrorCode>> results = new ArrayList<>();
			for (Future<Optional<ErrorCode>> future : futures) {
				// PolicyException 외의 예외는 여기서 터져 테스트를 실패시킨다 — 삼키지 않는다.
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private long successCount(List<Optional<ErrorCode>> results) {
		return results.stream().filter(Optional::isEmpty).count();
	}

	private List<ErrorCode> failures(List<Optional<ErrorCode>> results) {
		return results.stream().flatMap(Optional::stream).toList();
	}

	private Integer customRowCount() {
		return jdbc.queryForObject(
			"SELECT count(*) FROM blocked_extension WHERE type = 'CUSTOM'", Integer.class);
	}

	private Integer fixedRowCount() {
		return jdbc.queryForObject(
			"SELECT count(*) FROM blocked_extension WHERE type = 'FIXED'", Integer.class);
	}

	private Boolean isBlocked(String name) {
		return jdbc.queryForObject(
			"SELECT is_blocked FROM blocked_extension WHERE name = ?", Boolean.class, name);
	}

	private void fillCustomSlots(int count) {
		jdbc.batchUpdate(
			"INSERT INTO blocked_extension (name, type, is_blocked, custom_slot) VALUES (?, 'CUSTOM', TRUE, ?)",
			IntStream.rangeClosed(1, count)
				.mapToObj(i -> new Object[] { "c%03d".formatted(i), (short) i })
				.toList());
	}
}

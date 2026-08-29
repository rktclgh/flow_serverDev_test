package flow.test.serverdev.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * 토큰 버킷 자체를 본다. (SPEC §10.4, §21.9)
 *
 * <p><b>시간을 주입한다.</b> {@code System.nanoTime()} 에 기대면 "10개까지 통과" 같은
 * 단언이 실행 속도에 따라 흔들린다 — 초당 1개가 보충되므로 요청 사이에 1초가 지나면
 * 한 개가 더 통과한다. 그런 테스트는 실패해도 원인을 못 찾고, 통과해도 무엇을 확인한
 * 것인지 알 수 없다. 시계를 직접 밀면 경계가 정확히 관측된다.
 *
 * <p>배선(등록이 한 번뿐인가, 어느 경로에 걸리는가)은 {@code RateLimitFilterWiringTest} 가 본다.
 */
@DisplayName("속도 제한 필터")
class RateLimitFilterTest {

	private static final String IP = "203.0.113.7";
	private static final long SECOND = 1_000_000_000L;

	private final AtomicLong now = new AtomicLong(0);

	/** 분당 60개(초당 1개) · 용량 3 · 세대 10분 · 세대당 100항목. */
	private RateLimitFilter filter(int burst, int perMinute, Duration generation, int maxEntries) {
		return new RateLimitFilter(
			new RateLimitProperties(true, perMinute, burst, generation, maxEntries), now::get);
	}

	private RateLimitFilter filter() {
		return filter(3, 60, Duration.ofMinutes(10), 100);
	}

	/** {@code MockFilterChain} 은 두 번째 호출에서 터진다. 통과 횟수를 세야 하므로 직접 만든다. */
	private static final class CountingChain implements FilterChain {

		private final AtomicInteger passed = new AtomicInteger();

		@Override
		public void doFilter(ServletRequest request, ServletResponse response) {
			passed.incrementAndGet();
		}
	}

	private final CountingChain chain = new CountingChain();

	private MockHttpServletResponse request(RateLimitFilter filter, String ip) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/files");
		request.setRemoteAddr(ip);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);
		return response;
	}

	@Nested
	@DisplayName("토큰 버킷")
	class Bucket {

		@Test
		@DisplayName("용량만큼 통과시키고 그 다음부터 429 를 돌려준다")
		void burstThenReject() throws Exception {
			RateLimitFilter filter = filter();

			assertThat(request(filter, IP).getStatus()).isEqualTo(200);
			assertThat(request(filter, IP).getStatus()).isEqualTo(200);
			assertThat(request(filter, IP).getStatus()).isEqualTo(200);

			assertThat(request(filter, IP).getStatus()).isEqualTo(429);
			assertThat(chain.passed.get()).isEqualTo(3);
		}

		/**
		 * 거부는 <b>체인을 타지 않아야</b> 의미가 있다. 통과시킨 뒤 상태만 바꾸면
		 * multipart 파싱 비용을 이미 치른 뒤다.
		 */
		@Test
		@DisplayName("거부된 요청은 뒤로 넘어가지 않는다")
		void rejectedRequestStopsHere() throws Exception {
			RateLimitFilter filter = filter(1, 60, Duration.ofMinutes(10), 100);

			request(filter, IP);
			request(filter, IP);

			assertThat(chain.passed.get()).isEqualTo(1);
		}

		@Test
		@DisplayName("시간이 지난 만큼만 보충한다 — 1초에 한 개")
		void refills() throws Exception {
			RateLimitFilter filter = filter();
			request(filter, IP);
			request(filter, IP);
			request(filter, IP);

			now.addAndGet(SECOND);

			assertThat(request(filter, IP).getStatus()).isEqualTo(200);
			assertThat(request(filter, IP).getStatus()).isEqualTo(429);
		}

		@Test
		@DisplayName("오래 조용했어도 용량 이상으로는 쌓이지 않는다")
		void doesNotAccumulateBeyondBurst() throws Exception {
			RateLimitFilter filter = filter();
			request(filter, IP);

			now.addAndGet(60 * SECOND);

			assertThat(request(filter, IP).getStatus()).isEqualTo(200);
			assertThat(request(filter, IP).getStatus()).isEqualTo(200);
			assertThat(request(filter, IP).getStatus()).isEqualTo(200);
			assertThat(request(filter, IP).getStatus()).isEqualTo(429);
		}

		/** 한 IP 의 남용이 다른 사용자를 막으면 그것은 제한이 아니라 장애다. */
		@Test
		@DisplayName("주소가 다르면 버킷도 다르다")
		void bucketsArePerAddress() throws Exception {
			RateLimitFilter filter = filter(1, 60, Duration.ofMinutes(10), 100);

			assertThat(request(filter, "203.0.113.1").getStatus()).isEqualTo(200);
			assertThat(request(filter, "203.0.113.2").getStatus()).isEqualTo(200);
			assertThat(request(filter, "203.0.113.1").getStatus()).isEqualTo(429);
		}
	}

	@Nested
	@DisplayName("429 응답")
	class Rejection {

		@Test
		@DisplayName("RATE_LIMITED JSON 과 Retry-After 를 함께 준다")
		void body() throws Exception {
			RateLimitFilter filter = filter(1, 60, Duration.ofMinutes(10), 100);
			request(filter, IP);

			MockHttpServletResponse response = request(filter, IP);

			assertThat(response.getStatus()).isEqualTo(429);
			assertThat(response.getContentType()).isEqualTo("application/json");
			assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
			assertThat(response.getHeader("Retry-After")).isEqualTo("1");
		}

		/** 분당 1개면 다음 토큰까지 60초다. 임의의 상수가 아니라 실제 보충 속도에서 나온다. */
		@Test
		@DisplayName("Retry-After 는 보충 속도에서 계산한다")
		void retryAfterFollowsRefillRate() throws Exception {
			RateLimitFilter filter = filter(1, 1, Duration.ofMinutes(10), 100);
			request(filter, IP);

			assertThat(request(filter, IP).getHeader("Retry-After")).isEqualTo("60");
		}
	}

	/**
	 * ★ 이 필터가 <b>모르는 것</b>이 설계의 핵심이다(SPEC §21.1). 본문을 읽으면
	 * 거부할 요청의 파싱 비용을 이미 치른 것이고, 뒤에 오는 multipart 파서도 이미 소비된
	 * 스트림을 받게 된다.
	 */
	@Test
	@DisplayName("본문을 읽지 않는다")
	void doesNotReadBody() throws Exception {
		RateLimitFilter filter = filter(1, 60, Duration.ofMinutes(10), 100);

		for (int i = 0; i < 2; i++) {
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/files") {
				@Override
				public ServletInputStream getInputStream() {
					throw new AssertionError("속도 제한 필터가 본문을 읽었다");
				}
			};
			request.setRemoteAddr(IP);
			filter.doFilter(request, new MockHttpServletResponse(), chain);
		}
	}

	@Nested
	@DisplayName("세대 교체 — 상한을 지키는 방식")
	class Generations {

		/**
		 * 키가 클라이언트 주소이므로 상한이 없으면 <b>속도 제한 자체가 메모리 고갈 표면</b>이다.
		 * LRU 를 쓰지 않는 이유는 SPEC §21.9 에 있다 — 퇴출은 원자화되지 않는다.
		 */
		@Test
		@DisplayName("주소가 아무리 많이 와도 항목 수가 상한의 두 배를 넘지 않는다")
		void boundedByEntryCap() throws Exception {
			RateLimitFilter filter = filter(3, 60, Duration.ofMinutes(10), 10);

			for (int i = 0; i < 500; i++) {
				request(filter, "10.0." + (i / 250) + "." + (i % 250));
			}

			assertThat(filter.trackedKeys()).isLessThanOrEqualTo(20);
		}

		/**
		 * ★ <b>상한 검사와 삽입 사이가 원자적이어야 한다.</b> 밖에서 {@code size()} 를 재고
		 * 나서 넣으면, 상한 직전에 새 주소가 동시에 몰릴 때 <b>전부가 검사를 통과한 뒤 각자
		 * 삽입한다.</b> 그렇게 부푼 맵은 다음 교체에서 {@code previous} 로 그대로 넘어가므로
		 * 상한이 지키려던 메모리 보장이 깨진다.
		 *
		 * <p>순차 테스트({@code boundedByEntryCap})로는 이 결함이 드러나지 않는다 — 경합이
		 * 없으면 두 방식의 결과가 같다. 그래서 스레드를 겹쳐 돌리고, <b>불변식을 요청마다</b>
		 * 관측한다. 마지막에 한 번만 보면 그 사이에 부풀었다가 교체로 줄어든 순간을 놓친다.
		 */
		@Test
		@DisplayName("★ 새 주소가 동시에 몰려도 상한이 지켜진다")
		void capHoldsUnderConcurrency() throws Exception {
			int maxEntries = 8;
			int threads = 16;
			int perThread = 40;
			RateLimitFilter filter = filter(3, 60, Duration.ofMinutes(10), maxEntries);

			ExecutorService pool = Executors.newFixedThreadPool(threads);
			CountDownLatch start = new CountDownLatch(1);
			AtomicInteger worst = new AtomicInteger();
			List<Future<?>> running = new ArrayList<>();

			try {
				for (int t = 0; t < threads; t++) {
					int thread = t;
					running.add(pool.submit(() -> {
						start.await();
						for (int i = 0; i < perThread; i++) {
							// 스레드마다 다른 대역을 써서 키가 겹치지 않는다 — 전부 신규 항목이다.
							request(filter, "10.%d.%d.%d".formatted(thread, i / 250, i % 250));
							worst.accumulateAndGet(filter.trackedKeys(), Math::max);
						}
						return null;
					}));
				}
				start.countDown();
				for (Future<?> future : running) {
					future.get(30, TimeUnit.SECONDS);
				}
			}
			finally {
				pool.shutdownNow();
			}

			assertThat(worst.get())
				.as("한 세대는 상한을 넘길 수 없고, 직전 세대도 한때 그 상한을 지켰다")
				.isLessThanOrEqualTo(2 * maxEntries);
		}

		@Test
		@DisplayName("한 주기 동안 조용한 버킷은 두 번째 교체에서 버려진다")
		void quietBucketsAreDropped() throws Exception {
			RateLimitFilter filter = filter(3, 60, Duration.ofMinutes(10), 1000);
			for (int i = 0; i < 5; i++) {
				request(filter, "10.0.0." + i);
			}
			assertThat(filter.trackedKeys()).isEqualTo(5);

			now.addAndGet(Duration.ofMinutes(10).toNanos());
			request(filter, "198.51.100.1");
			now.addAndGet(Duration.ofMinutes(10).toNanos());
			request(filter, "198.51.100.2");

			assertThat(filter.trackedKeys()).isEqualTo(2);
		}

		/**
		 * 교체 경계에서 한도가 초기화되면 그 순간만 노려 무제한으로 보낼 수 있다.
		 * 활동 중인 버킷은 세대를 넘어 이어져야 한다.
		 */
		@Test
		@DisplayName("교체가 일어나도 활동 중인 버킷의 잔량은 이어진다")
		void activeBucketSurvivesRotation() throws Exception {
			// 교체 주기 1초 · 보충 분당 1개. 1초가 지나도 보충은 1/60 개뿐이라
			// "교체가 잔량을 되살렸는가" 만 남는다.
			RateLimitFilter filter = filter(3, 1, Duration.ofSeconds(1), 1000);
			request(filter, IP);
			request(filter, IP);
			request(filter, IP);

			now.addAndGet(SECOND);
			request(filter, "198.51.100.9");

			assertThat(request(filter, IP).getStatus()).isEqualTo(429);
		}
	}

	@Test
	@DisplayName("주소를 알 수 없어도 터지지 않는다")
	void unknownAddress() throws Exception {
		RateLimitFilter filter = filter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/files");
		request.setRemoteAddr(null);

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
	}
}

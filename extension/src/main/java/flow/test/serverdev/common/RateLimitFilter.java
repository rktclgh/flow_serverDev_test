package flow.test.serverdev.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 업로드 요청 속도를 IP 당으로 제한한다. (SPEC §10.4, §21.9)
 *
 * <p><b>서블릿 필터인 것이 요점이다.</b> multipart 파싱은 {@code DispatcherServlet} 안에서
 * 일어나므로 필터는 언제나 그보다 앞선다 — 거부할 요청의 파싱 비용을 치르지 않는다.
 * MVC interceptor 는 파싱 <b>뒤</b>라 이 자리에 쓸 수 없다.
 *
 * <p><b>이 필터가 모르는 것</b>이 설계다(SPEC §21.1). 아는 것은 클라이언트 주소와 시각뿐이고
 * <b>본문을 읽지 않는다.</b> 본문을 읽지 않고 429 를 돌려줘도 브라우저에
 * {@code ERR_CONNECTION_RESET} 이 뜨지 않는 것은 {@code server.tomcat.max-swallow-size: -1}
 * 덕분이다 — 크기 초과(413)를 위해 넣은 그 설정이 여기서 두 번째로 일한다.
 *
 * <h2>키는 {@code getRemoteAddr()} 만 쓴다</h2>
 *
 * {@code X-Forwarded-For} 를 직접 파싱하면 <b>헤더를 바꿔가며 버킷을 무한히 새로 만들 수
 * 있다.</b> 그러면 제한은 통째로 무력해지고 맵만 부푼다. Tomcat 의
 * {@code forward-headers-strategy: native} 가 신뢰 가능한 프록시 체인을 거쳐 이미 복원해 둔
 * 값을 그대로 쓴다 — 신뢰 판단을 여기서 다시 하지 않는다.
 *
 * <h2>상한은 세대(generation) 교체로 지킨다</h2>
 *
 * 키가 클라이언트 주소이므로 상한 없이 쌓으면 <b>속도 제한 자체가 메모리 고갈 표면</b>이 된다.
 *
 * <p>LRU 퇴출을 먼저 검토했다가 버렸다. {@code compute} 는 키 하나의 차감만 원자화할 뿐
 * "상한 확인 → 최장 미사용 항목 선택 → 삭제 → 삽입" 은 원자화하지 못한다. 동시 신규 IP 에서
 * 상한 초과·같은 victim 중복 퇴출·활성 버킷 삭제가 모두 가능하고, 전체 스캔이면 포화 후
 * 신규 키마다 O(항목 수)다. <b>동시성 버그를 감수하며 지킬 만한 정밀도가 아니다.</b>
 *
 * <p>세대 교체는 락이 없고 O(1)이며 메모리 상한이 자연히 유지된다. 맵 두 개를 두고 주기마다
 * (또는 항목 상한에 닿으면) old 를 통째로 버린다. <b>상한은 맵 크기를 재서 지키지 않는다</b> —
 * 재는 것과 넣는 것 사이가 원자적이지 않아 동시에 몰린 새 주소가 모두 검사를 통과한다.
 * 세대마다 입장 카운터를 두고 <b>삽입과 같은 연산 안에서</b> 증가시킨다. 직전 세대는 조회 시 상속되므로 <b>활동 중인
 * 버킷의 잔량은 교체를 넘어 이어진다</b> — 그렇지 않으면 교체 순간을 노려 한도를 초기화할 수
 * 있다. 대가는 두 주기 동안 조용했던 버킷이 초기화된다는 것인데, 그것을 감수할 수 있는 근거는
 * SPEC §10.4 의 역할 분담이다 — <b>앱 계층은 UX 용이고 실질 방어는 nginx</b> 다.
 */
public class RateLimitFilter implements Filter {

	/** 속도 제한을 거는 경로. 계약이므로 상수로 둔다. */
	static final String UPLOAD_PATH = "/api/files";

	/** 주소를 얻지 못한 요청이 모이는 키. {@code null} 을 맵에 넣을 수 없어 이름을 준다. */
	private static final String UNKNOWN_ADDRESS = "";

	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	private static final long SECONDS_PER_MINUTE = 60L;

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	private final int burst;
	private final long generationIntervalNanos;
	private final int maxEntries;

	/** 초당 보충량. 나눗셈을 요청마다 반복하지 않는다. */
	private final double tokensPerNano;

	private final LongSupplier nanoTime;
	private final AtomicReference<Generation> generation;

	public RateLimitFilter(RateLimitProperties properties, LongSupplier nanoTime) {
		this.burst = properties.burst();
		this.maxEntries = properties.maxEntries();
		this.generationIntervalNanos = properties.generationInterval().toNanos();
		this.tokensPerNano = (double) properties.perMinute() / (SECONDS_PER_MINUTE * NANOS_PER_SECOND);
		this.nanoTime = nanoTime;
		this.generation = new AtomicReference<>(new Generation(Map.of(), nanoTime.getAsLong()));
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		long now = nanoTime.getAsLong();
		String key = keyOf(request);
		Bucket bucket = consume(key, now);

		if (bucket.granted()) {
			chain.doFilter(request, response);
			return;
		}

		// 429 는 감사하지 않는다(SPEC §21.2). 판정에 도달하지 않았으므로 기록할 판정이 없고,
		// 기록하면 요청만으로 감사 테이블을 부풀릴 수 있다. 감사 실패는 fail-closed 503 이라
		// 그것이 곧 서비스 정지 수단이 된다. 남기는 것은 애플리케이션 로그뿐이다.
		long retryAfter = retryAfterSeconds(bucket.tokens());
		log.info("업로드 속도 제한: remoteAddr={}, retryAfter={}s", key, retryAfter);
		reject((HttpServletResponse) response, retryAfter);
	}

	/**
	 * <b>차감은 {@code compute} 한 번으로 끝낸다.</b> 읽고 계산해서 쓰면 그 사이에 다른 요청이
	 * 같은 버킷을 차감해 한도를 넘길 수 있다. 값이 불변 레코드라 "보충 → 차감 → 결과" 를
	 * 한 함수로 표현할 수 있고, 통과 여부까지 그 값에 실어 밖으로 꺼낸다.
	 *
	 * <p><b>새 항목의 입장 허가도 같은 함수 안에서 원자적으로 얻는다.</b> 재매핑 함수는 그 키에
	 * 대해 한 번만 실행되고 삽입까지 같은 잠금 안에서 끝나므로, 여기서 센 수와 실제로 들어간
	 * 항목 수가 어긋나지 않는다. {@code size()} 로 밖에서 세면 그 보장이 없다 — 아래
	 * {@link Generation#admit()} 주석 참고.
	 */
	private Bucket consume(String key, long now) {
		Generation current = rotateIfNeeded(now);
		Bucket updated = current.buckets().compute(key, (ignored, existing) -> {
			if (existing == null && current.admit() > maxEntries) {
				// 이 세대는 꽉 찼다. null 을 돌려주면 항목이 만들어지지 않는다.
				return null;
			}
			Bucket base = existing != null ? existing : current.inherit(key, burst, now);
			return base.refill(now, tokensPerNano, burst).consume();
		});

		// ★ 상한을 넘겨 추적하지 못한 요청은 <b>통과시킨다</b>(fail-open).
		//
		//   막는 쪽도 가능하지만 택하지 않았다. 이 맵을 채우려면 서로 다른 실제 출발지 주소가
		//   상한만큼 필요한데(키가 getRemoteAddr 다), 그것을 해낸 공격자는 곧바로 "그 시점 이후의
		//   모든 신규 사용자"를 막을 수 있게 된다. 정상 사용자를 막는 스위치를 공격자에게
		//   쥐여주는 셈이다.
		//
		//   통과시키는 대가는 그 주소가 앱 계층에서 잠시 측정되지 않는다는 것인데, 상한에 닿은
		//   세대는 바로 다음 요청에서 교체되므로 창이 짧고, 그동안에도 nginx 의 IP 당 제한은
		//   그대로 작동한다. SPEC §10.4 의 역할 분담 — 앱 계층은 UX 용, 실질 방어는 nginx —
		//   이 그대로 판단 근거다.
		return updated != null ? updated : Bucket.untracked();
	}

	/**
	 * 시간이 지났거나 항목이 상한에 닿으면 세대를 넘긴다.
	 *
	 * <p>포화 판단에 {@code buckets().size()} 를 쓰지 않는다. 크기를 재는 것과 삽입하는 것
	 * 사이가 원자적이지 않아, 상한 직전에 새 주소가 동시에 몰리면 <b>모두가 검사를 통과한 뒤
	 * 각자 삽입한다.</b> 그렇게 부푼 맵은 다음 교체에서 {@code previous} 로 그대로 넘어가므로,
	 * 상한이 지키려던 메모리 보장이 깨진다. 여기서는 <b>단조 증가하는 입장 카운터</b>만 읽는다 —
	 * 한 번 상한에 닿으면 되돌아가지 않으므로 밖에서 읽어도 판단이 뒤집히지 않는다.
	 *
	 * <p>CAS 에 실패하면 다른 스레드가 이미 교체한 것이므로 그 결과를 쓴다. 실패를 재시도하지
	 * 않는 이유는 교체가 <b>누가 하든 같은 일</b>이기 때문이다.
	 */
	private Generation rotateIfNeeded(long now) {
		Generation current = generation.get();
		boolean expired = now - current.startedAt() >= generationIntervalNanos;
		if (!expired && !current.isFull(maxEntries)) {
			return current;
		}
		Generation next = new Generation(current.buckets(), now);
		return generation.compareAndSet(current, next) ? next : generation.get();
	}

	/**
	 * 다음 토큰이 생길 때까지의 초. 올림하고 최소 1초를 보장한다 — 0을 주면 클라이언트가
	 * 즉시 재시도해 같은 거부를 반복한다.
	 */
	private long retryAfterSeconds(double tokens) {
		double needed = 1.0 - tokens;
		double seconds = needed / (tokensPerNano * NANOS_PER_SECOND);
		return Math.max(1L, (long) Math.ceil(seconds));
	}

	private static String keyOf(ServletRequest request) {
		String address = request.getRemoteAddr();
		return address == null || address.isBlank() ? UNKNOWN_ADDRESS : address;
	}

	/**
	 * 필터가 만드는 응답도 컨트롤러 응답과 <b>같은 형태</b>여야 한다({@link AdminTokenFilter}
	 * 와 같은 이유로 바이트를 직접 쓴다 — {@code charset} 파라미터가 붙으면 두 경로의 헤더가
	 * 달라진다).
	 */
	private static void reject(HttpServletResponse response, long retryAfterSeconds)
			throws IOException {

		byte[] body = """
				{"code":"%s","message":"요청이 너무 잦습니다. %d초 후에 다시 시도해 주세요."}"""
			.formatted(ErrorCode.RATE_LIMITED.name(), retryAfterSeconds)
			.getBytes(StandardCharsets.UTF_8);

		response.setStatus(ErrorCode.RATE_LIMITED.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
	}

	/** 테스트 관측용. 두 세대가 들고 있는 항목 수의 합이며 상한이 지켜지는지 보는 창이다. */
	int trackedKeys() {
		Generation current = generation.get();
		return current.buckets().size() + current.previous().size();
	}

	/**
	 * 한 세대. {@code buckets} 만 쓰이고 {@code previous} 는 조회 시 상속 원본으로만 읽힌다.
	 *
	 * @param previous  직전 세대. 참조를 끊는 순간 그 세대 전체가 회수된다
	 * @param startedAt 이 세대가 시작된 시각(nanos)
	 */
	private record Generation(Map<String, Bucket> previous, ConcurrentHashMap<String, Bucket> buckets,
			AtomicInteger admitted, long startedAt) {

		Generation(Map<String, Bucket> previous, long startedAt) {
			this(previous, new ConcurrentHashMap<>(), new AtomicInteger(), startedAt);
		}

		/**
		 * 새 항목 하나의 <b>입장 허가</b>를 원자적으로 얻는다. {@code compute} 의 재매핑 함수
		 * 안에서만 부른다 — 그 함수는 키당 한 번 실행되고 삽입까지 같은 잠금 안에서 끝나므로,
		 * 여기서 센 수는 실제 삽입 수를 <b>절대 밑돌지 않는다.</b>
		 *
		 * <p>그래서 반환값이 상한 이하일 때만 삽입하면 삽입 수는 상한을 넘을 수 없다.
		 * 스위퍼가 "조건부 UPDATE 로 청소 소유권을 먼저 얻는" 것과 같은 모양이다 —
		 * 확인과 행동을 <b>한 연산으로</b> 묶는다.
		 *
		 * @return 증가 후의 누적 입장 시도 수
		 */
		int admit() {
			return admitted.incrementAndGet();
		}

		/** 상한에 닿았는가. 카운터가 단조 증가라 밖에서 읽어도 판단이 뒤집히지 않는다. */
		boolean isFull(int maxEntries) {
			return admitted.get() >= maxEntries;
		}

		/** 직전 세대에 있던 버킷은 잔량째로 물려받는다. 없으면 가득 찬 새 버킷이다. */
		Bucket inherit(String key, int burst, long now) {
			Bucket carried = previous.get(key);
			return carried != null ? carried : new Bucket(burst, now, true);
		}
	}

	/**
	 * 토큰 버킷의 한 시점. <b>불변</b>이라 {@code compute} 안에서 통째로 교체된다.
	 *
	 * @param granted 이 값을 만든 차감이 통과했는지. 맵에 함께 담아 두면 {@code compute} 의
	 *                반환값만으로 판정을 꺼낼 수 있어 별도의 공유 상태가 필요 없다
	 */
	private record Bucket(double tokens, long updatedAt, boolean granted) {

		/**
		 * 세대가 꽉 차 추적하지 못한 요청의 결과. 맵에 들어가지 않으므로 잔량이 의미가 없고,
		 * {@code granted} 만 읽힌다.
		 */
		static Bucket untracked() {
			return new Bucket(0.0, 0L, true);
		}

		Bucket refill(long now, double tokensPerNano, int burst) {
			double replenished = tokens + Math.max(0L, now - updatedAt) * tokensPerNano;
			return new Bucket(Math.min(burst, replenished), now, granted);
		}

		Bucket consume() {
			return tokens >= 1.0
				? new Bucket(tokens - 1.0, updatedAt, true)
				: new Bucket(tokens, updatedAt, false);
		}
	}
}

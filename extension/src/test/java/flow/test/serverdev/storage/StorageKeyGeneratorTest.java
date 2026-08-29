package flow.test.serverdev.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 저장 키 생성. (SPEC §9)
 *
 * <p>파일명을 넘기는 인자가 아예 없으므로 "확장자가 키에 붙는" 경우는 컴파일 단계에서 불가능하다.
 * 대신 <b>키 전체 모양</b>을 정규식으로 고정해, 나중에 누가 파일명을 끼워 넣으면 여기서 깨지게 한다.
 */
@DisplayName("저장 키 생성")
class StorageKeyGeneratorTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	/** {@code yyyy/MM/dd/&#123;UUID&#125;} 외의 어떤 것도 들어가면 안 된다. */
	private static final String KEY_SHAPE =
		"\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	private static StorageKeyGenerator at(String instant, ZoneId zone) {
		return new StorageKeyGenerator(Clock.fixed(Instant.parse(instant), zone));
	}

	@Nested
	@DisplayName("키 모양")
	class Shape {

		@Test
		@DisplayName("날짜 세 단계 뒤에 UUID 가 붙는다")
		void dateThenUuid() {
			StorageKey key = at("2026-08-29T05:00:00Z", SEOUL).generate();

			assertThat(key.value()).matches(KEY_SHAPE);
			assertThat(key.value()).startsWith("2026/08/29/");
		}

		@Test
		@DisplayName("fileId 는 키의 마지막 세그먼트와 같다")
		void fileIdIsLastSegment() {
			StorageKey key = at("2026-08-29T05:00:00Z", SEOUL).generate();

			assertThat(key.value()).endsWith("/" + key.fileId());
		}

		@Test
		@DisplayName("한 자리 월·일도 0 을 채운다")
		void zeroPadded() {
			StorageKey key = at("2026-01-05T05:00:00Z", SEOUL).generate();

			assertThat(key.value()).startsWith("2026/01/05/");
		}
	}

	@Nested
	@DisplayName("시간대")
	class TimeZone {

		@Test
		@DisplayName("UTC 로는 아직 어제인 시각도 서울 날짜로 적힌다")
		void utcYesterdayIsSeoulToday() {
			// 2026-08-29T23:00Z == 2026-08-30 08:00 KST. 한국의 아침에 올린 파일이다.
			StorageKey key = at("2026-08-29T23:00:00Z", SEOUL).generate();

			assertThat(key.value()).startsWith("2026/08/30/");
		}

		@Test
		@DisplayName("서울 자정 1초 전은 아직 그 전날이다")
		void justBeforeSeoulMidnight() {
			// 2026-08-29T14:59:59Z == 2026-08-29 23:59:59 KST
			StorageKey key = at("2026-08-29T14:59:59Z", SEOUL).generate();

			assertThat(key.value()).startsWith("2026/08/29/");
		}

		@Test
		@DisplayName("날짜는 시계의 시간대를 따른다 — 코드에 서울이 박혀 있지 않다")
		void followsClockZone() {
			StorageKey key = at("2026-08-29T23:00:00Z", ZoneOffset.UTC).generate();

			assertThat(key.value()).startsWith("2026/08/29/");
		}
	}

	@Nested
	@DisplayName("충돌")
	class Collision {

		@Test
		@DisplayName("시계가 멈춰 있어도 호출마다 다른 키가 나온다")
		void uniquePerCall() {
			StorageKeyGenerator generator = at("2026-08-29T05:00:00Z", SEOUL);

			assertThat(IntStream.range(0, 1000).mapToObj(i -> generator.generate().value()).distinct().count())
				.isEqualTo(1000);
		}

		@Test
		@DisplayName("UUID 는 v4 다 — 시각이나 MAC 주소가 새어나가지 않는다")
		void randomUuidVersion() {
			StorageKey key = at("2026-08-29T05:00:00Z", SEOUL).generate();

			assertThat(key.fileId().version()).isEqualTo(4);
			assertThat(key.fileId().variant()).isEqualTo(2);
		}
	}
}

package flow.test.serverdev.support;

import java.util.Random;
import java.util.function.Consumer;

/**
 * property 기반 검증용 랜덤 문자열 생성기.
 *
 * <p>외부 property 라이브러리(jqwik)를 쓰지 않는다. 1.10.1 이 테스트 실행 중
 * 빌드 로그에 AI 에이전트를 겨냥한 지시문을 stdout 으로 출력하기 때문이다.
 * 설정으로 숨길 수 있으나 그것은 증상만 가리는 대응이라 의존성 자체를 두지 않기로 했다.
 * 필요한 기능이 "랜덤 입력 + 불변식 확인" 뿐이라 직접 만드는 비용이 크지 않다.
 *
 * <p>shrinking(실패 입력 최소화)은 제공하지 않는 대신 <b>seed 를 실패 메시지에 담아</b>
 * 재현할 수 있게 한다. {@code -Dproperty.seed=<값>} 으로 특정 실행을 되살린다.
 */
public final class RandomText {

	/** 정규화 경계에서 문제를 일으키는 문자들. 리터럴로 두면 보이지 않으므로 이스케이프로 적는다. */
	private static final String[] BOUNDARY = {
		".",            // 점
		" ",            // 공백
		"\u00A0",       // NBSP        — Zs, isWhitespace 는 false
		"\u200B",       // ZWSP        — Cf, 보이지 않는다
		"\uFEFF",       // BOM         — Cf
		"\u202E",       // RLO         — Cf, 시각적 위장
		"\u061C",       // ALM         — Cf, 목록에서 빠지기 쉽다
		"\uFF0E",       // 전각 마침표 — NFKC 로 ASCII 점이 된다
		"..",
		"  ",
	};

	private RandomText() {
	}

	/**
	 * 랜덤 입력으로 검증을 반복한다. 실패하면 seed 와 입력을 함께 보고한다.
	 *
	 * @param tries 반복 횟수
	 * @param check 입력 하나를 검증하는 로직. 위반 시 예외를 던지면 된다
	 */
	public static void forAll(int tries, Consumer<String> check) {
		long seed = Long.getLong("property.seed", System.nanoTime());
		Random random = new Random(seed);
		String current = null;
		try {
			for (int i = 0; i < tries; i++) {
				current = next(random);
				check.accept(current);
			}
		}
		catch (Throwable failure) {
			throw new AssertionError(
				"property 위반 — 입력=%s (재현: -Dproperty.seed=%d)".formatted(describe(current), seed),
				failure);
		}
	}

	/**
	 * 현실적인 입력과 극단적인 입력을 섞는다.
	 * 전부 랜덤 유니코드로 만들면 대부분이 거부 경로로만 흘러가
	 * 성공 경로의 불변식(멱등성 등)을 거의 검증하지 못한다.
	 */
	private static String next(Random random) {
		return switch (random.nextInt(5)) {
			case 0 -> ascii(random);
			case 1 -> ascii(random) + boundary(random);
			case 2 -> unicode(random, 0x0000, 0x2FFF);
			case 3 -> unicode(random, 0x0000, 0xFFFF);
			default -> filenameLike(random);
		};
	}

	private static String ascii(Random random) {
		int length = random.nextInt(25);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < length; i++) {
			builder.append((char) (random.nextBoolean()
				? 'a' + random.nextInt(26)
				: 'A' + random.nextInt(26)));
		}
		return builder.toString();
	}

	private static String boundary(Random random) {
		StringBuilder builder = new StringBuilder();
		int count = random.nextInt(3);
		for (int i = 0; i < count; i++) {
			builder.append(BOUNDARY[random.nextInt(BOUNDARY.length)]);
		}
		return builder.toString();
	}

	private static String unicode(Random random, int from, int to) {
		int length = random.nextInt(30);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < length; i++) {
			builder.append((char) (from + random.nextInt(to - from + 1)));
		}
		return builder.toString();
	}

	private static String filenameLike(Random random) {
		StringBuilder builder = new StringBuilder(ascii(random));
		int segments = random.nextInt(4);
		for (int i = 0; i < segments; i++) {
			builder.append(random.nextInt(10) == 0 ? "/" : ".").append(ascii(random));
		}
		return builder.append(boundary(random)).toString();
	}

	/** 실패 보고용 — 보이지 않는 문자를 코드포인트로 드러낸다. */
	public static String describe(String value) {
		if (value == null) {
			return "null";
		}
		StringBuilder builder = new StringBuilder("\"");
		value.codePoints().forEach(codePoint -> builder.append(
			codePoint >= 0x20 && codePoint <= 0x7E
				? String.valueOf((char) codePoint)
				: "\\u%04X".formatted(codePoint)));
		return builder.append('"').toString();
	}
}

package flow.test.serverdev.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 클라이언트 주소 해석. <b>이름 해석(DNS)을 유발하지 않는 것</b>이 이 클래스의 존재 이유다.
 *
 * <p>스프링도 컨테이너도 띄우지 않는다. 순수 함수라 빠르고, 실패하면 원인이 여기로 좁혀진다.
 */
@DisplayName("클라이언트 주소 해석")
class ClientAddressesTest {

	@Nested
	@DisplayName("리터럴은 받는다")
	class Literals {

		@Test
		@DisplayName("IPv4")
		void ipv4() {
			assertThat(ClientAddresses.parse("192.0.2.1"))
				.hasValueSatisfying(address ->
					assertThat(address.getHostAddress()).isEqualTo("192.0.2.1"));
		}

		@Test
		@DisplayName("IPv6")
		void ipv6() {
			assertThat(ClientAddresses.parse("2001:db8::1")).isPresent();
		}

		@Test
		@DisplayName("IPv6 루프백")
		void ipv6Loopback() {
			assertThat(ClientAddresses.parse("::1")).isPresent();
		}

		@Test
		@DisplayName("IPv4-mapped IPv6")
		void ipv4Mapped() {
			assertThat(ClientAddresses.parse("::ffff:192.0.2.1")).isPresent();
		}

		/** 일부 환경은 IPv6 를 대괄호로 감싸 전달한다. 벗기지 않으면 조용히 NULL 이 된다. */
		@Test
		@DisplayName("대괄호로 감싼 IPv6")
		void bracketed() {
			assertThat(ClientAddresses.parse("[2001:db8::1]")).isPresent();
		}

		@Test
		@DisplayName("존 인덱스가 붙은 링크로컬")
		void zoneIndex() {
			assertThat(ClientAddresses.parse("fe80::1%lo0")).isPresent();
		}

		@Test
		@DisplayName("앞뒤 공백은 무시한다")
		void trimmed() {
			assertThat(ClientAddresses.parse("  192.0.2.1  ")).isPresent();
		}
	}

	@Nested
	@DisplayName("★ 이름은 받지 않는다 — DNS 조회를 유발하지 않기 위해")
	class NeverResolvesNames {

		/**
		 * {@code localhost} 는 hosts 파일만으로 해석된다. 즉 이 단언은 네트워크 없이도
		 * "이름 해석 경로가 열려 있는가" 를 정확히 판정한다.
		 * 가드를 제거하면 곧바로 깨진다.
		 */
		@Test
		@DisplayName("localhost 는 해석하지 않는다")
		void localhost() {
			assertThat(ClientAddresses.parse("localhost")).isEmpty();
		}

		@Test
		@DisplayName("도메인 이름은 해석하지 않는다")
		void domainName() {
			assertThat(ClientAddresses.parse("attacker.example.com")).isEmpty();
		}

		/** 16진수 글자만으로 이루어진 호스트명. 문자 집합 검사만으로는 걸러지지 않는다. */
		@Test
		@DisplayName("16진수 글자로만 된 이름도 해석하지 않는다")
		void hexLookingName() {
			assertThat(ClientAddresses.parse("abc")).isEmpty();
			assertThat(ClientAddresses.parse("cafe")).isEmpty();
		}
	}

	@Nested
	@DisplayName("형식이 아닌 값은 버린다")
	class Rejected {

		@Test
		@DisplayName("범위를 벗어난 옥텟")
		void octetOutOfRange() {
			assertThat(ClientAddresses.parse("999.1.1.1")).isEmpty();
			assertThat(ClientAddresses.parse("256.0.0.1")).isEmpty();
		}

		@Test
		@DisplayName("자리 수가 모자란 IPv4")
		void shortIpv4() {
			assertThat(ClientAddresses.parse("192.0.2")).isEmpty();
		}

		@Test
		@DisplayName("유효하지 않은 IPv6")
		void malformedIpv6() {
			assertThat(ClientAddresses.parse(":::")).isEmpty();
		}

		@Test
		@DisplayName("빈 값과 null")
		void empty() {
			assertThat(ClientAddresses.parse(null)).isEmpty();
			assertThat(ClientAddresses.parse("")).isEmpty();
			assertThat(ClientAddresses.parse("   ")).isEmpty();
		}

		/** 여러 IP 가 콤마로 이어진 원본 헤더 값. 프록시가 골라준 단일 값만 받는다. */
		@Test
		@DisplayName("콤마로 이어진 목록은 받지 않는다")
		void forwardedList() {
			assertThat(ClientAddresses.parse("203.0.113.1, 198.51.100.2")).isEmpty();
		}
	}
}

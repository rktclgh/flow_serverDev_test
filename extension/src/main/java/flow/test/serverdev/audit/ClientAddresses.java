package flow.test.serverdev.audit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 요청에서 얻은 문자열을 클라이언트 주소로 해석한다.
 *
 * <p><b>{@code InetAddress.getByName()} 을 그냥 부르면 안 된다.</b> 리터럴 IP 가 아닌
 * 문자열을 주면 <b>DNS 조회를 수행</b>한다. 이 값은 결국 {@code X-Forwarded-For} 에서
 * 오고 그것은 클라이언트가 보낸 값이다 — 공격자가 호스트명을 넣으면 서버가 대신
 * DNS 질의를 쏘게 된다. 업로드 한 번마다 한 번씩, 공격자가 지정한 도메인으로.
 *
 * <p>그 자체로 외부 요청 유발(blind SSRF 신호)이고, 응답 지연을 만들며,
 * 서브도메인에 데이터를 실어 보내는 통로가 될 수도 있다.
 * <b>기록 하나 남기려다 요청 발신자가 되는 것은 남는 장사가 아니다.</b>
 *
 * <p>그래서 <b>리터럴만</b> 받는다. 해석되지 않으면 조용히 빈 값을 돌려주고
 * {@code client_ip} 는 NULL 로 남는다 — 주소를 모르는 것이 잘못된 주소를 남기는 것보다 낫다.
 */
public final class ClientAddresses {

	/** 점 4개 표기. 각 옥텟 0~255 를 정규식에서 직접 제한한다. */
	private static final Pattern IPV4 = Pattern.compile(
		"^((25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)$");

	/**
	 * 16진수·콜론·점(IPv4-mapped)만 허용한다.
	 *
	 * <p>호스트명은 콜론을 포함할 수 없으므로, 콜론을 요구하는 것만으로도 이름 해석 경로가 닫힌다.
	 * 문자 집합까지 좁힌 것은 그 위의 한 겹이다.
	 *
	 * <p><b>존 인덱스({@code %lo0})는 받지 않는다.</b> {@code InetAddress} 는 스코프를 유지하고
	 * {@code getHostAddress()} 가 {@code fe80:0:0:0:0:0:0:1%lo0} 을 돌려주는데,
	 * PostgreSQL 의 {@code INET} 은 스코프가 붙은 리터럴을 거부한다(숫자 스코프도 마찬가지).
	 * 그대로 두면 감사 INSERT 가 실패하고, 감사 실패는 fail-closed 이므로 요청이 503 이 된다.
	 * <b>이 값은 X-Forwarded-For 에서 오므로 공격자가 그 503 을 마음대로 유발할 수 있다.</b>
	 *
	 * <p>스코프를 떼어내 저장하는 방법도 있으나 택하지 않았다. 링크로컬 주소는 스코프를 빼면
	 * 서로 다른 호스트의 주소가 같은 값으로 뭉개진다 — 감사 기록에 그런 값을 남기는 것은
	 * 주소를 모르는 것보다 나쁘다.
	 */
	private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

	private ClientAddresses() {
	}

	public static Optional<InetAddress> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}

		String value = raw.strip();

		// [2001:db8::1] 형태로 오는 경우가 있다. 대괄호를 벗겨야 리터럴로 인식된다.
		if (value.startsWith("[") && value.endsWith("]")) {
			value = value.substring(1, value.length() - 1);
		}

		boolean literal = value.contains(":")
			? IPV6.matcher(value).matches()
			: IPV4.matcher(value).matches();

		if (!literal) {
			return Optional.empty();
		}

		try {
			// 여기 도달한 값은 리터럴이 확실하므로 이름 해석이 일어나지 않는다.
			return Optional.of(InetAddress.getByName(value));
		}
		catch (UnknownHostException exception) {
			// 형식은 통과했으나 실제로는 유효하지 않은 값(예: 콜론이 너무 많은 IPv6).
			return Optional.empty();
		}
	}
}

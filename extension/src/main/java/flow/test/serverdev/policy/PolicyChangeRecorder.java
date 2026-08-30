package flow.test.serverdev.policy;

import java.net.InetAddress;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 정책 변경 이력을 남긴다.
 *
 * <p><b>호출 위치가 설계의 핵심이다.</b> 컨트롤러가 아니라 정책을 실제로 바꾸는 트랜잭션
 * 안에서 부른다. 밖에서 부르면 정책은 바뀌었는데 이력만 빠지는 경로가 생기고, 그러면
 * 이력이 "일어난 일 전부" 라는 성질을 잃는다. 실패한 요청은 롤백과 함께 이력도 사라진다.
 *
 * <p>JPA 엔티티를 두지 않은 것도 의도다. 이 테이블은 <b>추가만 되고 읽히기만 한다</b>.
 * 영속성 컨텍스트가 관리할 상태 변화가 없으므로 매핑 계층이 얻는 것이 없고,
 * {@code INET} 캐스팅을 SQL 에 직접 쓰는 편이 무엇이 저장되는지 분명하다.
 */
@Component
public class PolicyChangeRecorder {

	private static final String INSERT = """
		INSERT INTO policy_change_log
		    (action, extension_name, client_ip, before_blocked, after_blocked)
		VALUES (?, ?, ?::inet, ?, ?)
		""";

	private final JdbcTemplate jdbc;

	public PolicyChangeRecorder(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 고정 확장자 토글.
	 *
	 * <p><b>상태가 바뀌지 않았으면 남기지 않는다.</b> 토글은 멱등이라 같은 값으로 다시 불러도
	 * 성공하는데, 그것까지 남기면 "언제 무엇이 바뀌었나" 를 묻는 조회가 바뀌지 않은 행으로
	 * 오염된다. DB 의 {@code ck_policy_change_transition} 도 같은 규칙을 강제한다.
	 */
	public void toggled(String name, boolean before, boolean after, InetAddress clientIp) {
		if (before == after) {
			return;
		}
		jdbc.update(INSERT,
			(after ? PolicyChangeAction.FIXED_BLOCK : PolicyChangeAction.FIXED_UNBLOCK).name(),
			name, address(clientIp), before, after);
	}

	/** 커스텀은 "행의 존재 = 차단" 이라 앞뒤 상태가 없다. */
	public void customAdded(String name, InetAddress clientIp) {
		jdbc.update(INSERT, PolicyChangeAction.CUSTOM_ADD.name(), name, address(clientIp), null, null);
	}

	public void customDeleted(String name, InetAddress clientIp) {
		jdbc.update(INSERT, PolicyChangeAction.CUSTOM_DELETE.name(), name, address(clientIp), null, null);
	}

	private static String address(InetAddress clientIp) {
		return clientIp == null ? null : clientIp.getHostAddress();
	}
}

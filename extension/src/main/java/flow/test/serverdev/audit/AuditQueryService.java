package flow.test.serverdev.audit;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 활동 로그 조회.
 *
 * <p><b>두 테이블을 DB 에서 합친다.</b> 앱에서 합치려면 양쪽을 각각 넉넉히 가져와 정렬한 뒤
 * 잘라야 하는데, 한쪽에 기록이 몰리면 그만큼이 버려진다. {@code UNION ALL} 로 합쳐 정렬과
 * 자르기를 DB 에 맡기면 필요한 만큼만 읽는다. 두 테이블 모두 {@code occurred_at} 에 인덱스가
 * 있어 정렬 비용도 붙지 않는다.
 *
 * <p>JPA 를 쓰지 않은 이유는 <b>이 질의의 결과가 엔티티가 아니기 때문</b>이다. 서로 다른
 * 두 테이블을 접어 만든 화면용 형태라 대응하는 테이블이 없다.
 */
@Service
public class AuditQueryService {

	/** 화면 한 번에 뿌릴 분량. 요청 하나가 감사 테이블 전체를 끌어오지 못하게 막는다. */
	static final int MAX_LIMIT = 200;
	static final int DEFAULT_LIMIT = 50;

	/**
	 * 정책 변경은 <b>바뀐 뒤의 차단 상태</b>를, 업로드는 판정과 사유를 {@code detail} 에 담는다.
	 *
	 * <p>커스텀 추가·삭제는 앞뒤 상태가 없지만(행의 존재 자체가 차단이다) 결과는 고정 확장자의
	 * 토글과 같으므로 같은 말로 적는다. 동작 이름을 되풀이하면 이 칸이 줄마다 다른 것을 뜻하게
	 * 되어 훑어 읽을 수 없다.
	 *
	 * <p>업로드의 {@code detail} 은 사유 코드와 걸린 확장자를 이어 붙인다. 로그가 "막혔다" 만
	 * 말하면 아무것도 답하지 못하므로 <b>왜</b>가 같은 줄에 있어야 한다.
	 */
	private static final String SQL = """
		SELECT occurred_at, 'POLICY' AS kind, action, extension_name AS target,
		       CASE WHEN after_blocked IS TRUE OR action = 'CUSTOM_ADD' THEN '차단 켜짐'
		            ELSE '차단 해제' END AS detail,
		       host(client_ip) AS client_ip
		  FROM policy_change_log
		UNION ALL
		SELECT occurred_at, 'UPLOAD' AS kind, result AS action, original_filename AS target,
		       concat_ws(' · ', reason_code, matched_extension, note) AS detail,
		       host(client_ip) AS client_ip
		  FROM upload_audit
		 ORDER BY occurred_at DESC, kind
		 LIMIT ?
		""";

	private final JdbcTemplate jdbc;

	public AuditQueryService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<AuditEntry> recent(Integer requested) {
		int limit = requested == null || requested <= 0
			? DEFAULT_LIMIT
			: Math.min(requested, MAX_LIMIT);

		return jdbc.query(SQL, (rs, row) -> new AuditEntry(
			rs.getObject("occurred_at", OffsetDateTime.class),
			rs.getString("kind"),
			rs.getString("action"),
			rs.getString("target"),
			// concat_ws 는 값이 하나도 없으면 빈 문자열을 준다. 화면에서 빈 칸으로 보이도록 null 로 접는다.
			emptyToNull(rs.getString("detail")),
			// host() 로 꺼내면 INET 의 넷마스크 표기 없이 주소만 나온다.
			rs.getString("client_ip")), limit);
	}

	private static String emptyToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}

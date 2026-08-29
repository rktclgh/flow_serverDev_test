package flow.test.serverdev.audit;

import java.net.InetAddress;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 업로드 시도 한 건의 기록. (SPEC §3.2)
 *
 * <p><b>기록을 실제로 지키는 것은 DB 트리거({@code trg_upload_audit_protect})다.</b>
 * 사실을 담은 필드에 붙은 {@code updatable = false} 는 보조 장치이며, 그 역할을 정확히
 * 적어둔다 — 뮤테이션으로 확인한 결과 처음 생각과 달랐다.
 *
 * <ul>
 *   <li>이 애노테이션이 <b>있으면</b>: 필드를 바꿔도 UPDATE 문에 실리지 않아
 *       <b>조용히 무시</b>된다. 메모리와 DB 가 어긋난 채로 진행된다
 *   <li><b>없으면</b>: 바뀐 값이 UPDATE 에 실려 트리거가 큰 소리로 거부한다
 * </ul>
 *
 * <p>감사 기록에서는 시끄러운 실패가 낫다. 그럼에도 남겨두는 이유는 이 필드들에
 * 애초에 변경 수단(setter)이 없어 두 경로 모두 도달하지 않고, 선언 자체가
 * "이 값은 바뀌지 않는다" 는 의도를 드러내기 때문이다.
 * <b>보증은 트리거가 한다</b>.
 *
 * <p>{@code clientIp} 는 {@link InetAddress} 다. Hibernate 는 이 타입을 기본으로
 * {@code INET} SQL 타입에 매핑한다({@code PostgreSQLInetJdbcType} 이 {@code PGobject(inet)}
 * 로 바인딩한다). 별도 애노테이션이 필요 없다.
 *
 * <p><b>주의</b>: PostgreSQL 의 {@code inet} 은 호스트뿐 아니라 네트워크
 * ({@code 192.168.1.0/24})도 담을 수 있는데 {@code InetAddress} 는 넷마스크를 표현하지 못한다.
 * 이 테이블은 클라이언트 <i>호스트</i> 주소만 기록하므로 해당되지 않지만,
 * 나중에 대역을 저장하려 든다면 타입을 다시 봐야 한다.
 */
@Entity
@Table(name = "upload_audit")
public class UploadAudit {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 시각의 주인은 DB 기본값이다. */
	@Column(name = "occurred_at", insertable = false, updatable = false)
	private OffsetDateTime occurredAt;

	@Column(name = "client_ip", updatable = false)
	private InetAddress clientIp;

	@Column(name = "original_filename", nullable = false, updatable = false)
	private String originalFilename;

	@Column(name = "size_bytes", updatable = false)
	private Long sizeBytes;

	/** 확정 전이 때문에 이 필드만 변경 가능하다. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private UploadResult result;

	/** PENDING → ERROR 전이에서 사유가 채워지므로 변경 가능하다. */
	@Column(name = "reason_code", length = 40)
	private String reasonCode;

	@Column(name = "matched_extension", length = 20, updatable = false)
	private String matchedExtension;

	@Column(length = 40, updatable = false)
	private String note;

	@Column(name = "stored_key", updatable = false)
	private String storedKey;

	protected UploadAudit() {
		// JPA
	}

	private UploadAudit(UploadAttempt attempt, UploadResult result, String reasonCode,
			String storedKey) {
		this.originalFilename = attempt.originalFilename();
		this.clientIp = attempt.clientIp();
		this.sizeBytes = attempt.sizeBytes();
		this.matchedExtension = attempt.matchedExtension();
		this.note = attempt.note();
		this.result = result;
		this.reasonCode = reasonCode;
		this.storedKey = storedKey;
	}

	/** 정책이 거부했다. 저장하지 않았으므로 키가 없다. */
	public static UploadAudit blocked(UploadAttempt attempt, String reasonCode) {
		return new UploadAudit(attempt, UploadResult.BLOCKED, reasonCode, null);
	}

	/**
	 * 저장을 시작하기 <b>전에</b> 자리를 잡는다. 키는 이미 정해져 있어야 한다.
	 *
	 * <p>이 행이 커밋된 뒤에야 스토리지를 호출한다. 그래서 DB 장애로 이 커밋이 실패하면
	 * 스토리지는 손도 대지 않은 상태이며, 정리할 찌꺼기가 없다.
	 */
	public static UploadAudit pending(UploadAttempt attempt, String storedKey) {
		return new UploadAudit(attempt, UploadResult.PENDING, null, storedKey);
	}

	/** 저장을 시도하기도 전에 실패했다. 키는 있을 수도 없을 수도 있다. */
	public static UploadAudit error(UploadAttempt attempt, String reasonCode, String storedKey) {
		return new UploadAudit(attempt, UploadResult.ERROR, reasonCode, storedKey);
	}

	/**
	 * 저장이 끝났다. <b>멱등하다</b> — 이미 {@code ALLOWED} 면 아무 일도 하지 않는다.
	 *
	 * <p>확정 커밋이 실패했는지 응답만 못 받았는지 호출자는 구분하지 못한다(SPEC §21.6).
	 * 두 번째 호출에서 예외가 나면, 실제로는 성공한 업로드를 실패로 보고하게 된다.
	 * {@code ERROR}·{@code BLOCKED} 에서의 전이는 그대로 막는다 — 그것은 되돌리기이지 재시도가 아니다.
	 */
	public void markAllowed() {
		if (result == UploadResult.ALLOWED) {
			return;
		}
		requirePending();
		this.result = UploadResult.ALLOWED;
	}

	/** 저장이 실패했다. */
	public void markError(String reasonCode) {
		requirePending();
		this.result = UploadResult.ERROR;
		this.reasonCode = reasonCode;
	}

	/**
	 * 확정된 기록은 다시 전이시킬 수 없다.
	 *
	 * <p>도메인에서 먼저 막는다. DB 트리거가 같은 규칙을 갖고 있지만, 그것은
	 * 이 경로를 우회한 변경을 위한 것이다. 여기서 막으면 오류가 훨씬 이른 시점에 드러난다.
	 */
	private void requirePending() {
		if (result != UploadResult.PENDING) {
			throw new IllegalStateException(
				"확정된 감사 기록은 변경할 수 없습니다: id=%s result=%s".formatted(id, result));
		}
	}

	public Long id() {
		return id;
	}

	public OffsetDateTime occurredAt() {
		return occurredAt;
	}

	public InetAddress clientIp() {
		return clientIp;
	}

	public String originalFilename() {
		return originalFilename;
	}

	public Long sizeBytes() {
		return sizeBytes;
	}

	public UploadResult result() {
		return result;
	}

	public String reasonCode() {
		return reasonCode;
	}

	public String matchedExtension() {
		return matchedExtension;
	}

	public String note() {
		return note;
	}

	public String storedKey() {
		return storedKey;
	}
}

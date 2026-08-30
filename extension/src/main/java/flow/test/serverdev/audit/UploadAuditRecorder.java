package flow.test.serverdev.audit;

import org.springframework.stereotype.Component;

import flow.test.serverdev.storage.StorageKey;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업로드 감사 기록의 단일 진입점. (SPEC §3.2, §8.2)
 *
 * <p><b>모든 메서드가 {@code REQUIRES_NEW} 다.</b> 기록은 호출자의 성패와 독립적으로
 * 남아야 한다. 호출자와 같은 트랜잭션에 묶이면, 요청을 거부하면서 롤백하는 순간
 * <b>거부했다는 사실 자체가 함께 사라진다</b>. 차단 기록이 필요한 상황이 정확히
 * 그 상황이므로, 이것이 붙어 있지 않으면 기능이 성립하지 않는다.
 *
 * <p>파일명 이스케이프를 여기서 적용한다. 호출부에 맡기면 언젠가 한 곳이 빠지고,
 * 그 한 곳이 하필 공격자가 보낸 파일명을 다루는 경로가 된다.
 *
 * <p><b>두 단계 기록</b>은 {@link #beginPending} → {@link #markAllowed}/{@link #markError} 다.
 * 저장을 시작하기 전에 자리를 잡아두므로, DB 장애로 첫 커밋이 실패하면 스토리지는
 * 손도 대지 않은 상태이고 정리할 찌꺼기가 없다.
 */
@Component
public class UploadAuditRecorder {

	private final UploadAuditRepository repository;

	public UploadAuditRecorder(UploadAuditRepository repository) {
		this.repository = repository;
	}

	/** 정책이 거부했다. 저장하지 않았으므로 이 한 번의 기록으로 끝난다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordBlocked(UploadAttempt attempt, String reasonCode) {
		repository.save(UploadAudit.blocked(sanitise(attempt), reasonCode));
	}

	/** 정책과 무관한 실패. 저장을 시도하기 전이라 키가 없다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordError(UploadAttempt attempt, String reasonCode) {
		repository.save(UploadAudit.error(sanitise(attempt), reasonCode));
	}

	/**
	 * 저장을 시작하기 <b>전에</b> 자리를 잡는다.
	 *
	 * <p>이 커밋이 끝난 뒤에야 스토리지를 호출해야 한다. 순서를 지키지 않으면
	 * 두 단계 기록이 보장하려던 성질이 사라진다.
	 *
	 * <p><b>{@link StorageKey} 를 통째로 받는다.</b> 키 문자열만 받으면 {@code file_id} 를
	 * 적을 자리가 없어 201 의 {@code fileId} 도 다운로드 조회도 성립하지 않는다. 둘을 따로 받는
	 * 형태도 가능하지만, 그러면 서로 다른 업로드의 키와 식별자를 섞어 넣을 수 있게 된다.
	 *
	 * @return 확정 시 사용할 기록 id
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public long beginPending(UploadAttempt attempt, StorageKey key) {
		return repository.save(UploadAudit.pending(sanitise(attempt), key)).id();
	}

	/** 저장이 끝났다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markAllowed(long auditId) {
		load(auditId).markAllowed();
	}

	/** 저장이 실패했다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markError(long auditId, String reasonCode) {
		load(auditId).markError(reasonCode);
	}

	/**
	 * 변경 감지에 맡긴다 — 트랜잭션이 끝날 때 flush 된다.
	 *
	 * <p>{@code result} 와 {@code reason_code} 외의 컬럼은 엔티티에서
	 * {@code updatable = false} 이고, 그마저 뚫려도 DB 트리거가 막는다.
	 */
	private UploadAudit load(long auditId) {
		return repository.findById(auditId)
			.orElseThrow(() -> new IllegalStateException(
				"확정할 감사 기록이 없습니다: id=%d".formatted(auditId)));
	}

	/**
	 * 파일명만 기록에 안전한 형태로 바꾼다. 나머지 값은 그대로 둔다 —
	 * 감사의 목적은 관측된 것을 그대로 남기는 것이다.
	 */
	private UploadAttempt sanitise(UploadAttempt attempt) {
		return new UploadAttempt(
			AuditFilenames.forRecord(attempt.originalFilename()),
			attempt.clientIp(),
			attempt.sizeBytes(),
			attempt.matchedExtension(),
			attempt.note());
	}
}

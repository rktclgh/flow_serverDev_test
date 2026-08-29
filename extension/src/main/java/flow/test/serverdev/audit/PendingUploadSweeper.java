package flow.test.serverdev.audit;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import flow.test.serverdev.storage.ObjectStorage;

/**
 * 확정되지 못한 채 남은 {@code PENDING} 감사 기록을 주기적으로 정리한다. (SPEC §8.2)
 *
 * <p><b>행을 지우지 않고 {@code ERROR} 로 전이시킨다.</b> 지우는 것은 스토리지의 고아
 * 객체뿐이다. 감사 기록은 "무엇이 왜 실패했는가" 를 답하기 위해 존재하므로, 실패한 업로드의
 * 흔적을 지우면 그 기능 자체가 사라진다 — 행을 지우면 "그런 일이 없었다" 와 구분되지 않는다.
 * DB 트리거({@code trg_upload_audit_protect})도 {@code PENDING -> ALLOWED|ERROR} 전이만
 * 허용해 같은 규칙을 강제한다.
 *
 * <p><b>순서가 중요하다</b> — 객체를 먼저 지우고, 성공했을 때만 행을 확정한다.
 * 객체 삭제가 실패하면 행은 {@code PENDING} 으로 남아 다음 주기에 다시 시도된다.
 * {@link ObjectStorage#delete} 는 없는 키를 지워도 실패로 보지 않으므로(S3 semantics),
 * 삭제가 이미 성공한 뒤 행 전이만 실패해 재시도가 객체 삭제를 다시 호출해도 안전하다.
 *
 */
public class PendingUploadSweeper {

	static final String REASON_UPLOAD_ABANDONED = "UPLOAD_ABANDONED";

	private static final Logger log = LoggerFactory.getLogger(PendingUploadSweeper.class);

	private final UploadAuditRepository repository;
	private final ObjectStorage objectStorage;
	private final PendingUploadSweeperProperties properties;

	public PendingUploadSweeper(UploadAuditRepository repository, ObjectStorage objectStorage,
			PendingUploadSweeperProperties properties) {
		this.repository = repository;
		this.objectStorage = objectStorage;
		this.properties = properties;
	}

	/**
	 * 한 주기의 정리를 실행한다. {@code before}(now - threshold) 보다 오래된
	 * {@code PENDING} 행을 최대 {@code batchSize} 개까지 대상으로 삼는다.
	 *
	 * <p>한 행의 처리가 실패해도 다음 행 처리를 계속한다 — 여기서 멈추면 잔여물이 계속
	 * 쌓인다. 실패한 행은 {@link #sweepOne} 이 {@code PENDING} 으로 남겨 다음 주기가
	 * 다시 집는다.
	 */
	/**
	 * <p>SpEL 빈 참조({@code #{@pendingUploadSweeperProperties…}})를 쓰지 않는다.
	 * {@code @EnableConfigurationProperties} 로 등록된 빈의 이름은 클래스명이 아니라
	 * 접두사가 붙은 이름이라 그 참조는 해석되지 않는다. 이 결함이 오래 드러나지 않았던 이유는
	 * <b>스위퍼가 등록된 적이 없어 {@code @Scheduled} 가 한 번도 평가되지 않았기</b> 때문이다.
	 * 프로퍼티 자리표시자는 값으로 치환되므로 그런 의존이 없다.
	 */
	@Scheduled(fixedRateString = "${app.audit.sweeper.interval:5m}",
			initialDelayString = "${app.audit.sweeper.interval:5m}")
	public void sweep() {
		OffsetDateTime before = OffsetDateTime.now().minus(properties.threshold());
		List<UploadAudit> stale = repository.findStalePending(before, PageRequest.of(0, properties.batchSize()));
		if (stale.isEmpty()) {
			return;
		}

		int cleaned = 0;
		for (UploadAudit audit : stale) {
			if (sweepOne(audit)) {
				cleaned++;
			}
		}
		log.info("PENDING 스윕 완료: {}건 정리 (대상 {}건)", cleaned, stale.size());
	}

	/**
	 * @return 이 행을 완전히 정리했으면 {@code true}. 실패했으면 {@code false} —
	 *         이 경우 행은 여전히 {@code PENDING} 이다.
	 */
	private boolean sweepOne(UploadAudit audit) {
		try {
			objectStorage.delete(audit.storedKey());
			markAbandoned(audit.id());
			return true;
		} catch (RuntimeException e) {
			log.warn("PENDING 정리 실패, 다음 주기에 재시도합니다: id={}, storedKey={}",
				audit.id(), audit.storedKey(), e);
			return false;
		}
	}

	/**
	 * {@code findById} 와 {@code save} 는 각각 스프링 데이터가 개별 트랜잭션으로 감싼다.
	 * 그래서 이 메서드 자체에는 {@code @Transactional} 이 필요 없다 — 붙이면 프록시를
	 * 거쳐야만 의미가 생기는데, 그 요건을 여기서 만들지 않는 편이 더 단순하다.
	 *
	 * <p>{@code markError} 는 {@code PENDING} 이 아닌 행에서 {@link IllegalStateException}
	 * 을 던진다. 조회 쿼리가 이미 {@code PENDING} 만 걸러내므로 정상 경로에서는 발생하지
	 * 않지만, 우회 경로로 이미 확정된 행을 건드리지 않는다는 것을 한 번 더 보장한다.
	 */
	private void markAbandoned(long auditId) {
		UploadAudit audit = repository.findById(auditId)
			.orElseThrow(() -> new IllegalStateException("정리할 감사 기록이 없습니다: id=" + auditId));
		audit.markError(REASON_UPLOAD_ABANDONED);
		repository.save(audit);
	}
}

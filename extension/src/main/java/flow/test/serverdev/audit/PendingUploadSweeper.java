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
 * <p><b>순서가 중요하다 — 청소 소유권을 먼저 얻는다.</b> (SPEC §21.6) 조건부 UPDATE 로
 * {@code PENDING} 행을 {@code ERROR} 로 확정하고, <b>그 UPDATE 가 실제로 1행을 바꿨을 때만</b>
 * 객체를 지운다. 0행이면 그 사이 업로드가 {@code ALLOWED} 로 확정된 것이므로 객체를 건드리지
 * 않고 넘어간다.
 *
 * <p>반대 순서(지우고 나서 확정)는 조용한 손실을 만든다. 임계 시간을 넘겼다는 것은 "느리다"
 * 는 뜻이지 "끝났다" 는 뜻이 아니라, 스위퍼가 객체를 지우는 사이에 {@code markAllowed} 가
 * 성공할 수 있다. 그러면 {@code ALLOWED} 행이 이미 없는 객체를 가리키는데, 스위퍼는
 * {@code PENDING} 만 보므로 <b>그 행을 두 번 다시 쳐다보지 않는다.</b> 아무도 눈치채지 못한다.
 *
 * <p><b>이 순서에서는 "삭제에 성공해야만 확정한다" 는 규칙이 성립하지 않는다.</b> 확정이 먼저이기
 * 때문이다. 삭제가 실패하면 고아 객체가 남지만, 행은 {@code ERROR} 로 남고 {@code stored_key}
 * 는 그대로라 <b>무엇이 남았는지 조회로 찾을 수 있다.</b> 추적 가능한 고아와, 아무도 모르게
 * 사라진 {@code ALLOWED} 객체 중에서는 앞의 것이 낫다 — 이 서비스가 지키려는 것이
 * "무엇이 왜 그렇게 됐는가" 를 답하는 능력이기 때문이다.
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
	 * 쌓인다. 소유권을 얻지 못한 행은 이미 남이 확정한 것이라 다시 볼 일이 없고, 객체 삭제에
	 * 실패한 행은 이미 {@code ERROR} 라 다음 주기의 조회에도 걸리지 않는다 — <b>재시도가
	 * 아니라 로그와 {@code stored_key} 로 남긴다</b>(위 클래스 주석 참고).
	 *
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
	 * 소유권을 얻고, 얻었을 때만 객체를 지운다.
	 *
	 * <p><b>두 단계를 각각 잡는다.</b> 한 덩어리로 묶으면 소유권 획득이 던진 예외와 삭제가
	 * 던진 예외가 구분되지 않는데, 둘은 남기는 상태가 다르다 — 앞은 행이 {@code PENDING}
	 * 이라 다음 주기가 다시 집고, 뒤는 행이 이미 {@code ERROR} 라 다시 집히지 않는다.
	 * 어느 쪽이든 <b>여기서 삼켜야</b> 한 행의 실패가 남은 행의 처리를 막지 않는다.
	 *
	 * @return 객체까지 지워 완전히 정리했으면 {@code true}. 소유권을 얻지 못했거나
	 *         (그 사이 확정됨) 삭제가 실패했으면 {@code false}
	 */
	private boolean sweepOne(UploadAudit audit) {
		int claimed;
		try {
			claimed = repository.claimAbandoned(audit.id(), REASON_UPLOAD_ABANDONED);
		} catch (RuntimeException e) {
			// 소유권을 못 얻었으므로 객체는 건드리지 않는다. 행은 PENDING 이라 다음 주기가 다시 집는다.
			log.warn("소유권 획득에 실패했습니다. 다음 주기에 다시 시도합니다: id={}", audit.id(), e);
			return false;
		}
		if (claimed == 0) {
			log.info("정리 대상이 아니게 됐습니다 — 그 사이 확정된 업로드입니다: id={}", audit.id());
			return false;
		}
		try {
			objectStorage.delete(audit.storedKey());
			return true;
		} catch (RuntimeException e) {
			log.warn("행은 확정했으나 객체를 지우지 못했습니다. 고아 객체가 남습니다 — "
					+ "stored_key 로 추적할 수 있습니다: id={}, storedKey={}",
				audit.id(), audit.storedKey(), e);
			return false;
		}
	}
}

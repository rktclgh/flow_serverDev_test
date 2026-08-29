package flow.test.serverdev.audit;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 감사 기록 저장소.
 *
 * <p>기록은 추가와 확정만 있고 수정이 없다. 그래서 조회 메서드도 최소로 둔다 —
 * 감사 테이블을 애플리케이션 로직의 조회 대상으로 쓰기 시작하면
 * "기록" 이 아니라 "상태" 가 되어 버린다.
 */
public interface UploadAuditRepository extends JpaRepository<UploadAudit, Long> {

	/**
	 * 확정되지 못한 채 남은 기록 전체. 스토리지에 객체만 있고 결말이 없는 경우다.
	 *
	 * <p>이 조회가 존재하는 이유는 <b>잔여물이 탐지 가능하다는 것을 코드로 보이기 위함</b>이다 —
	 * 두 단계 프로토콜이 주장하는 성질이 실제로 성립하는지 확인할 수 있어야 한다.
	 * 정리(스위퍼)는 {@link #findStalePending(OffsetDateTime, Pageable)} 을 쓴다 — 상한 없이
	 * 전체를 훑으면 한 주기가 다음 주기의 처리량까지 밀어낼 수 있다.
	 */
	@Query("SELECT a FROM UploadAudit a WHERE a.result = flow.test.serverdev.audit.UploadResult.PENDING "
		+ "AND a.occurredAt < :before ORDER BY a.occurredAt")
	List<UploadAudit> findStalePending(@Param("before") OffsetDateTime before);

	/**
	 * 확정되지 못한 채 남은 기록을, 한 번에 최대 {@code pageable} 크기만큼 조회한다.
	 *
	 * <p>스위퍼 전용. 배치 상한을 쿼리 레벨(LIMIT)에서 강제해, 애플리케이션이 전체를
	 * 읽어들인 뒤 잘라내는 방식보다 한 주기의 부하가 실제로 상한을 넘지 않는다.
	 */
	@Query("SELECT a FROM UploadAudit a WHERE a.result = flow.test.serverdev.audit.UploadResult.PENDING "
		+ "AND a.occurredAt < :before ORDER BY a.occurredAt")
	List<UploadAudit> findStalePending(@Param("before") OffsetDateTime before, Pageable pageable);
}

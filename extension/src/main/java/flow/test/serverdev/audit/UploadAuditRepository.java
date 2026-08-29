package flow.test.serverdev.audit;

import java.time.OffsetDateTime;
import java.util.List;

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
	 * 확정되지 못한 채 남은 기록. 스토리지에 객체만 있고 결말이 없는 경우다.
	 *
	 * <p>정리 작업(스위퍼)은 아직 없다. 이 조회가 존재하는 이유는
	 * <b>잔여물이 탐지 가능하다는 것을 코드로 보이기 위함</b>이다 —
	 * 두 단계 프로토콜이 주장하는 성질이 실제로 성립하는지 확인할 수 있어야 한다.
	 */
	@Query("SELECT a FROM UploadAudit a WHERE a.result = flow.test.serverdev.audit.UploadResult.PENDING "
		+ "AND a.occurredAt < :before ORDER BY a.occurredAt")
	List<UploadAudit> findStalePending(@Param("before") OffsetDateTime before);
}

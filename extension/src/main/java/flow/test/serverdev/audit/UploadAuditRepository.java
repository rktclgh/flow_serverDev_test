package flow.test.serverdev.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

	/**
	 * 다운로드가 내보낼 행. (SPEC §7.6)
	 *
	 * <p><b>{@code result} 를 조회 조건에 넣는다.</b> 행을 읽어와서 상태를 보고 거르면 그
	 * 분기를 지워도 대개 "객체가 없어서" 404 가 나와, 방어가 사라진 것을 아무도 눈치채지
	 * 못한다. 조건이 쿼리에 있으면 그 상태의 행은 <b>애초에 손에 들어오지 않는다.</b>
	 *
	 * <p>감사 테이블을 조회 대상으로 쓰는 유일한 지점이다. 이 행이 곧 "그 파일이 무엇이었나"
	 * 의 기록이고, 같은 사실을 담는 테이블을 하나 더 두면 둘이 어긋날 자리만 는다.
	 */
	Optional<UploadAudit> findByFileIdAndResult(UUID fileId, UploadResult result);

	/**
	 * 이 기록의 <b>청소 소유권</b>을 얻으면서 동시에 {@code ERROR} 로 확정한다. (SPEC §21.6)
	 *
	 * <p>{@code WHERE ... AND result = PENDING} 이 이 쿼리의 전부다. 읽고 나서 판단하고
	 * 쓰는 것이 아니라 <b>조건과 갱신이 한 문장</b>이라, 그 사이에 다른 트랜잭션이 끼어들 창이
	 * 없다. 행 잠금은 DB 가 이 UPDATE 안에서 알아서 잡는다.
	 *
	 * <p><b>스위퍼가 조회한 뒤 업로드가 {@code markAllowed} 로 확정될 수 있다.</b> 임계 시간이
	 * 지났다는 것은 "느리다" 는 뜻이지 "끝났다" 는 뜻이 아니다. 그때 스위퍼가 객체부터 지우면
	 * {@code ALLOWED} 행이 이미 없는 객체를 가리키고, 상태가 {@code ALLOWED} 라 스위퍼는
	 * 다시 쳐다보지도 않는다 — <b>아무도 눈치채지 못하는 손실</b>이다.
	 *
	 * @return 갱신된 행 수. <b>1이면 소유권을 얻었다</b> — 그때만 객체를 지운다.
	 *         <b>0이면 그 사이 확정된 것</b>이므로 객체를 건드리지 않는다
	 */
	@Modifying
	@Transactional
	@Query("UPDATE UploadAudit a SET a.result = flow.test.serverdev.audit.UploadResult.ERROR, "
		+ "a.reasonCode = :reasonCode "
		+ "WHERE a.id = :id AND a.result = flow.test.serverdev.audit.UploadResult.PENDING")
	int claimAbandoned(@Param("id") long id, @Param("reasonCode") String reasonCode);
}

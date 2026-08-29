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
	 * 목록에 내보낼 행. (GET /api/files)
	 *
	 * <p><b>{@code deletedAt IS NULL} 을 조회 조건에 넣는다.</b> {@code result} 를 조건에 넣는
	 * 것과 같은 이유다 — 읽어와서 걸러내면 그 분기를 지워도 대개 티가 나지 않는다.
	 * 조건이 쿼리에 있으면 지운 파일은 <b>애초에 손에 들어오지 않는다.</b>
	 *
	 * <p>{@code ORDER BY occurred_at DESC} 는 부분 인덱스 {@code idx_upload_audit_visible} 와
	 * 같은 모양이다. 조건과 정렬이 인덱스와 어긋나면 목록 조회가 매번 전체를 훑는다.
	 *
	 * @param pageable 상한. {@code LIMIT} 을 쿼리 레벨에서 강제한다 — 전부 읽어온 뒤 잘라내면
	 *                 행이 쌓일수록 조회 비용이 그대로 늘어난다
	 */
	@Query("SELECT a FROM UploadAudit a "
		+ "WHERE a.result = flow.test.serverdev.audit.UploadResult.ALLOWED "
		+ "AND a.deletedAt IS NULL ORDER BY a.occurredAt DESC")
	List<UploadAudit> findVisible(Pageable pageable);

	/**
	 * 다운로드가 내보낼 행. (SPEC §7.6)
	 *
	 * <p><b>{@code result} 를 조회 조건에 넣는다.</b> 행을 읽어와서 상태를 보고 거르면 그
	 * 분기를 지워도 대개 "객체가 없어서" 404 가 나와, 방어가 사라진 것을 아무도 눈치채지
	 * 못한다. 조건이 쿼리에 있으면 그 상태의 행은 <b>애초에 손에 들어오지 않는다.</b>
	 *
	 * <p>이 행이 곧 "그 파일이 무엇이었나" 의 기록이고, 같은 사실을 담는 테이블을 하나 더
	 * 두면 둘이 어긋날 자리만 는다.
	 *
	 * <p><b>{@code deletedAt IS NULL} 도 같은 이유로 조건이다.</b> 지운 파일은 객체가 없으므로
	 * 조건을 빼도 "객체가 없어서" 404 가 나오지만, 그것은 우연이다 — 객체 삭제가 실패한
	 * 행에서는 지운 파일이 그대로 내려간다.
	 */
	Optional<UploadAudit> findByFileIdAndResultAndDeletedAtIsNull(UUID fileId, UploadResult result);

	/**
	 * 이 파일의 <b>삭제 소유권</b>을 얻으면서 동시에 지웠다고 확정한다.
	 *
	 * <p>{@link #claimAbandoned} 와 같은 형태이며 같은 이유다(SPEC §21.6). 읽고 나서 판단하고
	 * 쓰는 것이 아니라 <b>조건과 갱신이 한 문장</b>이라, 그 사이에 다른 요청이 끼어들 창이 없다.
	 *
	 * <p><b>여기가 삭제의 유일한 관문이다.</b> 상태·중복 판정을 미리 조회해서 거르지 않는다 —
	 * 그러면 이 WHERE 절이 실제로는 아무것도 지키지 않게 되고, 조건을 지워도 앞단의 조회가
	 * 대신 막아버려 <b>방어가 사라진 것을 아무도 눈치채지 못한다.</b>
	 *
	 * <p>객체를 지우기 <b>전에</b> 이것이 커밋돼야 한다. 순서를 뒤집으면 객체 삭제 뒤 행 갱신이
	 * 실패했을 때 {@code deleted_at} 이 NULL 인데 객체가 없는 행이 남는다 — 목록에는 보이는데
	 * 다운로드는 404 다. 그래서 호출자에 바깥 트랜잭션을 걸지 않는다.
	 *
	 * <p>시각은 {@code CURRENT_TIMESTAMP} 로 <b>DB 가</b> 찍는다. 애플리케이션 시계를 쓰면
	 * 인스턴스마다 다른 시각이 들어가 {@code occurred_at} 과 같은 기준으로 비교할 수 없게 된다.
	 *
	 * @return 갱신된 행 수. <b>1이면 소유권을 얻었다</b> — 그때만 객체를 지운다.
	 *         <b>0이면</b> 없거나, 이미 지웠거나, {@code ALLOWED} 가 아닌 것이다 — 셋을 구분하지 않는다
	 */
	@Modifying
	@Transactional
	@Query("UPDATE UploadAudit a SET a.deletedAt = CURRENT_TIMESTAMP "
		+ "WHERE a.fileId = :fileId "
		+ "AND a.result = flow.test.serverdev.audit.UploadResult.ALLOWED "
		+ "AND a.deletedAt IS NULL")
	int markDeleted(@Param("fileId") UUID fileId);

	/**
	 * 지울 객체의 키. 소유권을 얻은 <b>뒤에</b> 읽는다.
	 *
	 * <p>이 조회에는 상태 조건이 없다 — 판정은 이미 {@link #markDeleted} 가 끝냈다. 여기에
	 * 조건을 또 걸면 같은 규칙이 두 곳에 생기고, 둘이 어긋날 때 어느 쪽이 사실인지 알 수 없다.
	 */
	@Query("SELECT a.storedKey FROM UploadAudit a WHERE a.fileId = :fileId")
	Optional<String> findStoredKey(@Param("fileId") UUID fileId);

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

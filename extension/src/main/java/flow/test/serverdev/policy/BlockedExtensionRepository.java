package flow.test.serverdev.policy;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import flow.test.serverdev.policy.domain.BlockedExtension;
import flow.test.serverdev.policy.domain.ExtensionType;

/**
 * 정책 저장소. 최대 207행이므로 전체 조회가 기본 접근 방식이다.
 *
 * <p>인덱스를 이름·슬롯 UNIQUE 와 type 외에 더 만들지 않은 것은 <b>성능 판단의 결과</b>다.
 * 이 크기에서는 seq scan 이 인덱스 조회보다 빠르다(SPEC §3.1).
 */
public interface BlockedExtensionRepository
		extends JpaRepository<BlockedExtension, Long>, PolicySlotRepository {

	/**
	 * 이름으로 조회한다. 고정/커스텀을 구분하지 않는 것이 핵심이다 —
	 * 커스텀에 {@code exe} 를 넣으려는 시도를 <b>한 번의 조회</b>로 잡아낸다.
	 */
	Optional<BlockedExtension> findByName(String name);

	/**
	 * 종류만 조회한다. 삭제 판정에는 엔티티 전체가 필요 없다 —
	 * <b>엔티티를 로드하면 그것을 지우고 싶어진다</b>. 그 유혹을 타입으로 차단한다.
	 */
	@Query("SELECT b.type FROM BlockedExtension b WHERE b.name = :name")
	Optional<ExtensionType> findTypeByName(@Param("name") String name);

	/**
	 * 커스텀 확장자를 조건부로 삭제하고 <b>지운 행 수</b>를 돌려준다.
	 *
	 * <p>Spring Data 의 파생 삭제({@code deleteByName})나 {@code delete(entity)} 를 쓰지 않는다.
	 * 그것들은 엔티티를 로드한 뒤 지우므로, 두 요청이 같은 행을 읽고 둘 다 삭제를 시도한다.
	 * 진 쪽은 "1행을 지워야 하는데 0행"이 되어 {@code ObjectOptimisticLockingFailureException}
	 * 으로 터지고, 사용자에게는 404 여야 할 상황이 <b>500</b> 으로 나간다.
	 *
	 * <p>지운 행 수로 판정하면 경합이 자연스럽게 해소된다 — 0행은 "이미 없다"는 뜻이고,
	 * 그것이 곧 호출자가 원한 결과 상태다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("DELETE FROM BlockedExtension b WHERE b.name = :name AND b.type = :type")
	int deleteByNameAndType(@Param("name") String name, @Param("type") ExtensionType type);
}

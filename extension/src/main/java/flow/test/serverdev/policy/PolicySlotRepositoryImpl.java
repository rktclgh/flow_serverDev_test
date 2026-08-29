package flow.test.serverdev.policy;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link PolicySlotRepository} 구현. 이름이 {@code <프래그먼트>Impl} 이어야
 * Spring Data 가 리포지토리에 합성한다.
 */
class PolicySlotRepositoryImpl implements PolicySlotRepository {

	/**
	 * advisory lock 키. 임의의 상수이지만 <b>다른 용도와 겹치지 않아야</b> 하므로
	 * 용도를 알 수 있는 값을 쓴다. 같은 DB 를 쓰는 다른 기능이 같은 키를 잡으면
	 * 서로 관계없는 작업이 직렬화된다.
	 */
	private static final long POLICY_LOCK_KEY = 0x45_58_54_47_55_41_52_44L; // "EXTGUARD"

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	public void lockPolicy() {
		// pg_advisory_xact_lock 은 void 를 반환한다. 드라이버마다 void 매핑이 달라
		// 결과 타입에 의존하지 않도록 상수 1을 감싸 반환한다.
		// 이 함수는 VOLATILE 이라 옵티마이저가 제거하지 않는다.
		entityManager
			.createNativeQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS acquired")
			.setParameter("key", POLICY_LOCK_KEY)
			.getResultList();
	}

	@Override
	public Optional<Short> findFirstFreeSlot(int limit) {
		List<?> rows = entityManager.createNativeQuery("""
				SELECT s FROM generate_series(1, :limit) AS s
				LEFT JOIN blocked_extension b ON b.custom_slot = s
				WHERE b.id IS NULL
				ORDER BY s
				LIMIT 1
				""")
			.setParameter("limit", limit)
			.getResultList();

		return rows.stream().findFirst().map(value -> ((Number) value).shortValue());
	}
}

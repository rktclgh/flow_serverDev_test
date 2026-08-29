package flow.test.serverdev.policy;

import java.util.Optional;

/**
 * 슬롯 할당과 정책 잠금. Spring Data 의 파생 쿼리로 표현할 수 없는 두 가지를 담는다.
 *
 * <p>서비스에 네이티브 SQL 을 두지 않기 위해 <b>리포지토리 프래그먼트</b>로 분리했다.
 * 서비스가 SQL 을 알기 시작하면 "정책 규칙"과 "저장 방식"이 한 클래스에서 섞인다.
 */
public interface PolicySlotRepository {

	/**
	 * 정책 변경을 직렬화한다. 트랜잭션 종료 시 자동 해제된다.
	 *
	 * <p>DB 레벨 잠금이므로 <b>인스턴스가 여러 대여도</b> 직렬화된다.
	 * JVM 의 {@code synchronized} 로는 얻을 수 없는 성질이다.
	 */
	void lockPolicy();

	/**
	 * 1..limit 중 비어 있는 가장 작은 슬롯. 없으면 상한에 도달한 것이다.
	 *
	 * <p>삭제로 비워진 슬롯이 자동으로 재사용된다 — 삭제/추가를 반복해도 상한이 잠식되지 않는다.
	 */
	Optional<Short> findFirstFreeSlot(int limit);
}

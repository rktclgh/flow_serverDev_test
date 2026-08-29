package flow.test.serverdev.audit;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * PENDING 스위퍼 설정. (SPEC §8.2 — {@code PENDING} 자동 정리)
 *
 * <p>{@link #threshold()} 는 <b>정상 업로드를 죽이지 않기 위한 안전판</b>이다. 저장을
 * 시작한 직후의 행도 잠깐 {@code PENDING} 이므로, 임계 없이 쓸면 진행 중인 업로드를
 * 고아로 오판해 지운다. 기본값 10분은 실서비스 업로드 시간보다 넉넉히 크게 잡는다.
 *
 * <p><b>잘못된 값이면 기동을 막는다.</b> 이 세 값은 틀렸을 때 실패하는 방향이 서로 다르고,
 * 셋 다 조용하다.
 *
 * <ul>
 *   <li>{@code batchSize} 가 0 이하면 {@code PageRequest.of} 가 <b>매 주기 예외</b>를
 *       던진다. 스케줄러는 예외를 삼키고 다음 주기를 잡으므로 스위퍼는 영영 아무 일도
 *       하지 않는다 — 기능이 죽었는데 애플리케이션은 멀쩡히 떠 있다</li>
 *   <li>{@code threshold} 가 0 이하면 방금 시작한 업로드까지 청소 대상이 된다.
 *       <b>이쪽이 더 위험하다</b> — 죽는 게 아니라 사용자 파일을 지운다</li>
 *   <li>{@code interval} 이 0 이하면 스케줄 등록 자체가 실패한다</li>
 * </ul>
 *
 * <p>그래서 {@code @Validated} 로 <b>바인딩 시점</b>에 걸러 기동을 멈춘다. 조용히
 * 비활성화되거나 삭제 범위가 넓어지는 것보다, 못 뜨고 로그에 이유가 찍히는 편이 낫다.
 *
 * <p>하한은 "양수" 까지만 강제한다. {@code 1ms} 처럼 위험할 만큼 짧은 값도 통과하는데,
 * 그것은 <b>일부러 적은 값</b>이라 여기서 판단할 문제가 아니다. 여기서 막는 것은
 * 오타·미설정·기본값 0 같은 <b>의도하지 않은 값</b>이다.
 *
 * @param enabled   스위퍼 활성화 여부. 꺼두면 스케줄러 등록 자체를 하지 않는다
 * @param interval  주기 실행 간격
 * @param threshold 이 시간보다 오래된 {@code PENDING} 행만 대상으로 삼는다
 * @param batchSize 한 주기에 처리할 최대 행 수. 무제한으로 두면 한 번의 지연 사고가
 *                  다음 주기의 처리량까지 밀어낼 수 있다
 */
@Validated
@ConfigurationProperties(prefix = "app.audit.sweeper")
public record PendingUploadSweeperProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("5m") @DurationMin(nanos = 1) Duration interval,
		@DefaultValue("10m") @DurationMin(nanos = 1) Duration threshold,
		@DefaultValue("100") @Positive int batchSize) {
}

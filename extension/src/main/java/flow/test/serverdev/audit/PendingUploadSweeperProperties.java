package flow.test.serverdev.audit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PENDING 스위퍼 설정. (SPEC §8.2 — {@code PENDING} 자동 정리)
 *
 * <p>{@link #threshold()} 는 <b>정상 업로드를 죽이지 않기 위한 안전판</b>이다. 저장을
 * 시작한 직후의 행도 잠깐 {@code PENDING} 이므로, 임계 없이 쓸면 진행 중인 업로드를
 * 고아로 오판해 지운다. 기본값 10분은 실서비스 업로드 시간보다 넉넉히 크게 잡는다.
 *
 * @param enabled   스위퍼 활성화 여부. 꺼두면 스케줄러 등록 자체를 하지 않는다
 * @param interval  주기 실행 간격
 * @param threshold 이 시간보다 오래된 {@code PENDING} 행만 대상으로 삼는다
 * @param batchSize 한 주기에 처리할 최대 행 수. 무제한으로 두면 한 번의 지연 사고가
 *                  다음 주기의 처리량까지 밀어낼 수 있다
 */
@ConfigurationProperties(prefix = "app.audit.sweeper")
public record PendingUploadSweeperProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("5m") Duration interval,
		@DefaultValue("10m") Duration threshold,
		@DefaultValue("100") int batchSize) {
}

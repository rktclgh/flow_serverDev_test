package flow.test.serverdev.storage;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 저장 키를 만든다. (SPEC §9)
 *
 * <p>날짜 프리픽스는 <b>주입된 {@link Clock} 의 시간대</b>를 따른다. 시스템 기본값을 쓰지 않는데,
 * 앱이 도는 컨테이너({@code eclipse-temurin:21-jre-alpine})에 {@code TZ} 가 없어 UTC 로 뜨기
 * 때문이다. 호스트가 {@code Asia/Seoul} 이어도 컨테이너는 UTC 이므로, 시스템 기본값을 따르면
 * <b>한국 시간 오전 0~9시에 올린 파일이 전날 프리픽스로 들어간다.</b> 게다가 조용히 그렇게 된다.
 */
public class StorageKeyGenerator {

	/** 날짜 프리픽스. 버킷을 사람이 훑을 때 읽히고, 보존기간 정리를 프리픽스 단위로 할 수 있다. */
	private static final DateTimeFormatter DATE_PREFIX = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	private final Clock clock;

	public StorageKeyGenerator(Clock clock) {
		this.clock = clock;
	}

	/**
	 * 새 저장 키를 만든다. <b>입력을 받지 않는다</b> — 파일명이 키에 섞일 경로 자체가 없다.
	 */
	public StorageKey generate() {
		UUID fileId = UUID.randomUUID();
		return new StorageKey(fileId, LocalDate.now(clock).format(DATE_PREFIX) + "/" + fileId);
	}
}

package flow.test.serverdev.support;

/**
 * 테스트가 띄우는 컨테이너 이미지. <b>한 곳에만 적는다.</b>
 *
 * <p>이전에는 네 개의 테스트 클래스가 각자 문자열을 들고 있었다. 그러면 한 곳만 올렸을 때
 * 테스트마다 다른 버전에서 도는 상태가 되고, 그 사실을 아무도 알아채지 못한다.
 * 스키마 제약을 검증하는 테스트와 애플리케이션을 검증하는 테스트가 서로 다른 DB 를 쓰는 것은
 * 이 프로젝트가 계속 경계해온 "테스트한 것과 배포하는 것이 다르다" 의 한 형태다.
 *
 * <p><b>17 인 이유</b>: 배포 대상 서버의 PostgreSQL 이 17.10 이다. 18 로 테스트하면
 * 통과해도 배포 환경을 검증한 것이 아니다. 호스트에 설치된 psql 클라이언트가 18.4 라
 * 서버도 18 이라고 착각하기 쉬운데, {@code SELECT current_setting('server_version')} 으로
 * 확인한 실제 서버는 17.10 이었다.
 */
public final class TestImages {

	/** 배포 대상 서버와 같은 메이저 버전이어야 한다. */
	public static final String POSTGRES = "postgres:17-alpine";

	/**
	 * MinIO. <b>{@code latest} 를 쓰지 않는다</b> — 같은 커밋이 어제와 오늘 다른 이미지에서
	 * 도는 것은 이 클래스가 막으려는 바로 그 상황이다. 태그를 고정하면 이미지가 바뀌는 시점이
	 * 커밋으로 남는다.
	 */
	public static final String MINIO = "minio/minio:RELEASE.2025-09-07T16-13-09Z";

	private TestImages() {
	}
}

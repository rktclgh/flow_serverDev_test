package flow.test.serverdev.policy;

/**
 * DB 제약 위반이 <b>어느 제약</b>에서 났는지 판별한다.
 *
 * <p>드라이버·JPA 구현이 예외를 여러 겹으로 감싸므로 원인 사슬 전체를 훑는다.
 * Hibernate 의 {@code ConstraintViolationException#getConstraintName()} 을 쓰지 않는 이유는
 * 그 값이 방언과 버전에 따라 채워지지 않을 때가 있어서다. Postgres 는 메시지에 제약 이름을
 * 항상 담는다({@code duplicate key value violates unique constraint "..."}).
 *
 * <p><b>별도 클래스로 뽑은 이유</b>: 서비스 안에 두면 검증할 수가 없다. 이 판별식은
 * advisory lock 덕분에 실제로는 거의 도달하지 않는 경로라, 테스트가 없으면
 * <b>틀려도 아무도 모르는 코드</b>가 된다. 밖으로 꺼내면 실제 위반 예외로 직접 검증할 수 있다.
 */
final class ConstraintViolations {

	private ConstraintViolations() {
	}

	/** 예외의 원인 사슬 어딘가에 해당 제약 이름이 등장하는가. */
	static boolean involves(Throwable exception, String constraintName) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			String message = cause.getMessage();
			if (message != null && message.contains(constraintName)) {
				return true;
			}
		}
		return false;
	}
}

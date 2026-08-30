package flow.test.serverdev.policy;

/** 정책에 일어날 수 있는 변경. DB 의 {@code ck_policy_change_action} 과 값이 같아야 한다. */
public enum PolicyChangeAction {

	FIXED_BLOCK,
	FIXED_UNBLOCK,
	CUSTOM_ADD,
	CUSTOM_DELETE
}

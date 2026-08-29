package flow.test.serverdev.policy.domain;

/**
 * 확장자 정책의 두 종류. (SPEC §3.1)
 *
 * <p>테이블을 나누지 않고 한 테이블 + 이 구분자로 둔 이유는 두 가지다.
 * 업로드 검증이 <b>단일 쿼리</b>로 끝나고, 고정과 커스텀의 이름 겹침이
 * {@code UNIQUE(name)} 하나로 자동 차단된다.
 */
public enum ExtensionType {

	/** 과제가 지정한 7개. 체크/해제만 가능하고 추가·삭제·개명이 불가능하다. */
	FIXED,

	/** 사용자가 추가한 확장자. <b>행의 존재 자체가 차단</b>을 의미한다. */
	CUSTOM
}

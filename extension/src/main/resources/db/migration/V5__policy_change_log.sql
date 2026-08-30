-- 정책 변경 이력
--
-- 왜 필요한가
--
--   upload_audit 은 "무엇이 왜 차단됐는가" 를 답한다. 그런데 그 판정의 기준이었던
--   정책 자체가 언제 어떻게 바뀌었는지는 어디에도 남지 않았다. 같은 파일이 어제는
--   통과하고 오늘은 막혔을 때, 그 사이에 무슨 일이 있었는지 설명할 근거가 없다.
--
-- actor 컬럼을 두지 않는 이유
--
--   관리 토큰이 하나뿐이라 "누가" 를 채우면 항상 같은 값이 된다. 상수를 컬럼으로 두면
--   그 컬럼이 무언가를 구분해준다고 오해하게 만든다. 인증이 들어오는 시점에 추가한다.
--   지금 구분에 쓸 수 있는 것은 client_ip 뿐이므로 그것만 남긴다.

CREATE TABLE policy_change_log (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 무엇을 했는가. 고정은 토글, 커스텀은 추가·삭제뿐이다.
    action         VARCHAR(20) NOT NULL,

    -- 대상 확장자. 정규화된 값만 들어간다 — blocked_extension.name 과 같은 규칙이다.
    extension_name VARCHAR(20) NOT NULL,

    -- upload_audit.client_ip 와 같은 이유로 INET 이다.
    -- 문자열로 두면 같은 IPv6 주소가 여러 표기로 갈라져 집계가 불가능해진다.
    client_ip      INET,

    -- 토글의 앞뒤 상태. 커스텀 추가·삭제는 "행의 존재 = 차단" 이라 NULL 이다.
    before_blocked BOOLEAN,
    after_blocked  BOOLEAN,

    CONSTRAINT ck_policy_change_action CHECK (
        action IN ('FIXED_BLOCK', 'FIXED_UNBLOCK', 'CUSTOM_ADD', 'CUSTOM_DELETE')
    ),

    CONSTRAINT ck_policy_change_extension CHECK (
        extension_name ~ '^[a-z0-9]{1,20}$'
    ),

    -- 토글에만 앞뒤 상태가 있고, 있으면 서로 달라야 한다.
    -- 같은 값으로 토글한 요청은 상태를 바꾸지 않았으므로 이력에 남길 변경이 없다.
    --
    -- ★ CHECK 는 3값 논리라 IS NULL 을 명시하지 않으면 NULL 행이 판정을 빠져나간다.
    CONSTRAINT ck_policy_change_transition CHECK (
        (action IN ('FIXED_BLOCK', 'FIXED_UNBLOCK')
             AND before_blocked IS NOT NULL AND after_blocked IS NOT NULL
             AND before_blocked <> after_blocked)
        OR
        (action IN ('CUSTOM_ADD', 'CUSTOM_DELETE')
             AND before_blocked IS NULL AND after_blocked IS NULL)
    )
);

-- 최근 변경 조회가 기본 접근 패턴이다.
CREATE INDEX idx_policy_change_occurred_at ON policy_change_log (occurred_at DESC);

-- 특정 확장자의 이력을 따라가는 조회.
CREATE INDEX idx_policy_change_extension ON policy_change_log (extension_name, occurred_at DESC);


-- 이력은 고쳐 쓸 수 없다.
--
-- upload_audit 과 같은 원칙이다. 다만 이쪽은 상태 전이가 없으므로 UPDATE 를 통째로 막는다.
-- DELETE 는 막지 않는다 — 보존 기간 정리는 운영의 정상적인 행위다.
CREATE OR REPLACE FUNCTION extguard_protect_policy_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'policy change log is append-only (id=%)', OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_policy_change_protect
    BEFORE UPDATE ON policy_change_log
    FOR EACH ROW EXECUTE FUNCTION extguard_protect_policy_change();

-- TRUNCATE 는 행 단위 트리거를 실행하지 않으므로 따로 막는다.
CREATE OR REPLACE FUNCTION extguard_prevent_policy_change_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'policy_change_log cannot be truncated';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_policy_change_prevent_truncate
    BEFORE TRUNCATE ON policy_change_log
    FOR EACH STATEMENT EXECUTE FUNCTION extguard_prevent_policy_change_truncate();

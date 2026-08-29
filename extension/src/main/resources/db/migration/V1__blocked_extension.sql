-- 확장자 차단 정책
--
-- 설계 원칙: 도메인 불변식을 애플리케이션이 아니라 DB 가 지킨다.
-- 앱의 검사는 사용자에게 친절한 오류를 주기 위한 것이고,
-- 정합성 보증은 아래 제약과 트리거가 담당한다. API 를 우회해도 지켜져야 한다.

CREATE TABLE blocked_extension (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- 정규화된 확장자. 앞의 점 없이 소문자 영숫자만.
    name        VARCHAR(20) NOT NULL,

    -- FIXED: 과제가 지정한 고정 7개. 체크/해제 대상이며 삭제할 수 없다.
    -- CUSTOM: 사용자가 추가한 확장자. 행의 존재 자체가 차단을 의미한다.
    type        VARCHAR(10) NOT NULL,

    is_blocked  BOOLEAN     NOT NULL DEFAULT FALSE,

    -- 커스텀 확장자의 1~200 슬롯. 200개 상한을 선언적으로 보증하는 장치다.
    custom_slot SMALLINT,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 이름 중복 방지. 고정과 커스텀 사이의 겹침(커스텀에 'exe' 입력)도 이 하나로 막힌다.
    -- 앱의 exists() 체크는 동시 요청에서 뚫리므로 이것이 실제 방어선이다.
    CONSTRAINT uq_blocked_extension_name UNIQUE (name),

    -- 슬롯 중복 방지. PostgreSQL 의 UNIQUE 는 NULL 을 서로 다른 값으로 취급하므로
    -- FIXED 7행이 모두 NULL 을 가져도 충돌하지 않는다.
    CONSTRAINT uq_blocked_extension_slot UNIQUE (custom_slot),

    CONSTRAINT ck_blocked_extension_type CHECK (type IN ('FIXED', 'CUSTOM')),

    -- 앱 정규화(ExtensionNormalizer)의 최종 보증.
    -- 정규식은 ExtensionNormalizer.ALLOWED 와 글자 그대로 같아야 한다.
    -- 두 규칙이 어긋나면 이중 방어의 의미가 사라진다.
    CONSTRAINT ck_blocked_extension_format CHECK (name ~ '^[a-z0-9]{1,20}$'),

    -- CUSTOM 은 "행의 존재 = 차단". 토글 대상은 FIXED 뿐이라는 도메인 규칙.
    CONSTRAINT ck_custom_always_blocked CHECK (type = 'FIXED' OR is_blocked = TRUE),

    -- 타입별 슬롯 규칙.
    --
    -- ★ custom_slot IS NOT NULL 을 명시해야 한다.
    --   SQL 의 CHECK 는 3값 논리라 결과가 NULL 이면 '통과'로 취급한다.
    --   IS NOT NULL 없이 (type='CUSTOM' AND custom_slot BETWEEN 1 AND 200) 만 쓰면
    --   슬롯이 NULL 일 때 TRUE AND NULL = NULL 이 되어 CUSTOM 행이 슬롯 없이 저장된다.
    --   그러면 200개 상한이 무력화된다.
    CONSTRAINT ck_custom_slot CHECK (
        (type = 'FIXED'  AND custom_slot IS NULL)
        OR
        (type = 'CUSTOM' AND custom_slot IS NOT NULL AND custom_slot BETWEEN 1 AND 200)
    )
);

-- 인덱스는 UNIQUE 2개와 type 만 둔다.
-- 최대 207행(고정 7 + 커스텀 200)이라 전체 조회는 seq scan 이 더 빠르다.
-- 인덱스를 더 만들지 않은 것이 성능 판단의 결과다.
CREATE INDEX idx_blocked_extension_type ON blocked_extension (type);


-- updated_at 자동 갱신.
-- DEFAULT now() 만으로는 INSERT 시각만 남고 UPDATE 후에도 그대로다.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_blocked_extension_updated_at
    BEFORE UPDATE ON blocked_extension
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- 고정 확장자 삭제 방지.
-- 애플리케이션에서만 막으면 직접 SQL·배치·새 API 가 우회할 수 있다.
CREATE OR REPLACE FUNCTION prevent_fixed_delete() RETURNS trigger AS $$
BEGIN
    IF OLD.type = 'FIXED' THEN
        RAISE EXCEPTION 'FIXED extension cannot be deleted: %', OLD.name;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_blocked_extension_prevent_fixed_delete
    BEFORE DELETE ON blocked_extension
    FOR EACH ROW EXECUTE FUNCTION prevent_fixed_delete();


-- 고정 확장자 시드. 과제가 지정한 순서를 id 순서로 보존한다(화면 표시 순서).
-- 기본값은 unCheck 이므로 is_blocked = FALSE.
INSERT INTO blocked_extension (name, type, is_blocked) VALUES
    ('bat', 'FIXED', FALSE),
    ('cmd', 'FIXED', FALSE),
    ('com', 'FIXED', FALSE),
    ('cpl', 'FIXED', FALSE),
    ('exe', 'FIXED', FALSE),
    ('scr', 'FIXED', FALSE),
    ('js',  'FIXED', FALSE);

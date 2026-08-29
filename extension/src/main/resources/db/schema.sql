-- =============================================================================
-- ExtGuard 전체 스키마 (단일 진실 원천)
-- =============================================================================
--
-- 이 파일은 현재 스키마 전체를 한눈에 보기 위한 정본이다.
-- 실제 적용은 Flyway 가 db/migration/*.sql 을 순서대로 실행해서 이루어진다.
-- 스키마를 바꿀 때는 migration 을 추가하고 이 파일도 함께 갱신한다.
--
-- 적용 방식
--   신규 설치 : Flyway 가 migration 을 처음부터 실행
--   기존 DB   : 새 migration 만 증분 적용
--   이 파일   : 실행되지 않는다 (Flyway locations 는 classpath:db/migration)
--
-- 설계 원칙
--   도메인 불변식을 애플리케이션이 아니라 DB 가 지킨다.
--   앱 검사는 사용자에게 친절한 오류를 주기 위한 것이고,
--   정합성 보증은 아래 제약과 트리거가 담당한다. API 를 우회해도 지켜져야 한다.
--
-- 반영된 migration : V1
-- =============================================================================


-- -----------------------------------------------------------------------------
-- blocked_extension — 확장자 차단 정책
-- -----------------------------------------------------------------------------
--
-- 컬럼
--   id           BIGINT       PK, 자동 증가
--   name         VARCHAR(20)  정규화된 확장자 (앞의 점 없이 소문자 영숫자)
--   type         VARCHAR(10)  FIXED | CUSTOM
--   is_blocked   BOOLEAN      차단 여부. FIXED 만 토글 대상
--   custom_slot  SMALLINT     커스텀 확장자의 1~200 슬롯. FIXED 는 NULL
--   created_at   TIMESTAMPTZ
--   updated_at   TIMESTAMPTZ  트리거가 자동 갱신
--
-- 고정/커스텀을 한 테이블에 둔 이유
--   업로드 검증이 단일 쿼리로 끝나고, 고정·커스텀 이름 겹침(커스텀에 'exe' 입력)이
--   UNIQUE(name) 하나로 자동 차단된다. 테이블을 나누면 둘 다 앱 코드로 처리해야 하고
--   그 코드는 동시 요청에서 뚫린다.

CREATE TABLE blocked_extension (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(20) NOT NULL,
    type        VARCHAR(10) NOT NULL,
    is_blocked  BOOLEAN     NOT NULL DEFAULT FALSE,
    custom_slot SMALLINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 이름 중복 방지. 앱의 exists() 체크는 동시 요청에서 뚫리므로 이것이 실제 방어선이다.
    CONSTRAINT uq_blocked_extension_name UNIQUE (name),

    -- 슬롯 중복 방지.
    -- PostgreSQL 의 UNIQUE 는 NULL 을 서로 다른 값으로 취급하므로
    -- FIXED 7행이 모두 NULL 을 가져도 충돌하지 않는다.
    CONSTRAINT uq_blocked_extension_slot UNIQUE (custom_slot),

    CONSTRAINT ck_blocked_extension_type CHECK (type IN ('FIXED', 'CUSTOM')),

    -- 앱 정규화(ExtensionNormalizer)의 최종 보증.
    -- 이 정규식은 ExtensionNormalizer.ALLOWED 와 글자 그대로 같아야 한다.
    CONSTRAINT ck_blocked_extension_format CHECK (name ~ '^[a-z0-9]{1,20}$'),

    -- CUSTOM 은 "행의 존재 = 차단". 토글 대상은 FIXED 뿐이다.
    CONSTRAINT ck_custom_always_blocked CHECK (type = 'FIXED' OR is_blocked = TRUE),

    -- 타입별 슬롯 규칙 — 200개 상한을 선언적으로 보증한다.
    --
    -- ★ custom_slot IS NOT NULL 이 반드시 필요하다.
    --   SQL 의 CHECK 는 3값 논리라 결과가 NULL 이면 통과로 취급한다.
    --   IS NOT NULL 없이 쓰면 CUSTOM 행의 슬롯이 NULL 일 때
    --   TRUE AND NULL = NULL -> FALSE OR NULL = NULL -> 통과가 되어
    --   슬롯 없는 커스텀 행이 무제한 저장되고 200개 상한이 무력화된다.
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


-- -----------------------------------------------------------------------------
-- 트리거
-- -----------------------------------------------------------------------------

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


-- -----------------------------------------------------------------------------
-- 시드
-- -----------------------------------------------------------------------------
-- 과제가 지정한 고정 확장자 7개. INSERT 순서가 곧 화면 표시 순서다.
-- 기본값은 unCheck 이므로 is_blocked = FALSE.

INSERT INTO blocked_extension (name, type, is_blocked) VALUES
    ('bat', 'FIXED', FALSE),
    ('cmd', 'FIXED', FALSE),
    ('com', 'FIXED', FALSE),
    ('cpl', 'FIXED', FALSE),
    ('exe', 'FIXED', FALSE),
    ('scr', 'FIXED', FALSE),
    ('js',  'FIXED', FALSE);


-- -----------------------------------------------------------------------------
-- 커스텀 확장자 슬롯 할당 참고 쿼리
-- -----------------------------------------------------------------------------
-- 추가 시 빈 슬롯 하나를 찾는다. 없으면 200개가 찼다는 뜻이다.
--
--   SELECT s FROM generate_series(1, 200) s
--   LEFT JOIN blocked_extension b ON b.custom_slot = s
--   WHERE b.id IS NULL
--   ORDER BY s LIMIT 1;

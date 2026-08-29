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
-- 반영된 migration : V1, V2, V3, V4
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
    ),

    -- 고정 확장자의 이름을 과제가 지정한 7개로 못박는다.
    -- 이것이 없으면 type='FIXED' 로 임의의 행을 무한히 추가할 수 있다.
    -- UNIQUE(name) 과 결합해 FIXED 는 최대 7행이 된다.
    CONSTRAINT ck_fixed_names CHECK (
        type <> 'FIXED'
        OR name IN ('bat', 'cmd', 'com', 'cpl', 'exe', 'scr', 'js')
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
CREATE OR REPLACE FUNCTION extguard_set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_blocked_extension_updated_at
    BEFORE UPDATE ON blocked_extension
    FOR EACH ROW EXECUTE FUNCTION extguard_set_updated_at();

-- 고정 확장자 삭제 방지.
-- 애플리케이션에서만 막으면 직접 SQL·배치·새 API 가 우회할 수 있다.
CREATE OR REPLACE FUNCTION extguard_prevent_fixed_delete() RETURNS trigger AS $$
BEGIN
    IF OLD.type = 'FIXED' THEN
        RAISE EXCEPTION 'FIXED extension cannot be deleted: %', OLD.name;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_blocked_extension_prevent_fixed_delete
    BEFORE DELETE ON blocked_extension
    FOR EACH ROW EXECUTE FUNCTION extguard_prevent_fixed_delete();

-- 고정 확장자 행의 변조 방지.
--
-- 삭제 트리거만으로는 부족하다. OLD.type 만 검사하므로 다음 순서로 우회된다.
--   UPDATE blocked_extension SET type='CUSTOM', custom_slot=1 WHERE name='exe';
--   DELETE FROM blocked_extension WHERE name='exe';   -- 이미 CUSTOM 이라 통과
-- ON CONFLICT (name) DO UPDATE 로도 같은 변환이 가능하다.
--
-- 고정 확장자에서 바뀌어도 되는 것은 is_blocked(체크/해제) 뿐이다.
CREATE OR REPLACE FUNCTION extguard_protect_fixed_row() RETURNS trigger AS $$
BEGIN
    IF OLD.type = 'FIXED' THEN
        IF NEW.name <> OLD.name
           OR NEW.type <> OLD.type
           OR NEW.custom_slot IS DISTINCT FROM OLD.custom_slot THEN
            RAISE EXCEPTION 'FIXED extension is immutable except is_blocked: %', OLD.name;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_blocked_extension_protect_fixed_row
    BEFORE UPDATE ON blocked_extension
    FOR EACH ROW EXECUTE FUNCTION extguard_protect_fixed_row();

-- TRUNCATE 차단.
--
-- TRUNCATE 는 행 단위 DELETE 트리거를 실행하지 않으므로 고정 7개가 통째로 사라진다.
-- 문 단위 BEFORE TRUNCATE 트리거로 막는다.
--
-- 한계: 테이블 소유자는 트리거를 비활성화하거나 제약을 제거할 수 있다.
-- 완전한 방어는 마이그레이션 계정과 런타임 계정을 분리하고
-- 런타임 역할에서 DDL/TRUNCATE 권한을 회수하는 것이다.
CREATE OR REPLACE FUNCTION extguard_prevent_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'blocked_extension cannot be truncated';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_blocked_extension_prevent_truncate
    BEFORE TRUNCATE ON blocked_extension
    FOR EACH STATEMENT EXECUTE FUNCTION extguard_prevent_truncate();


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
-- upload_audit — 업로드 감사 기록
-- -----------------------------------------------------------------------------
--
-- 컬럼
--   id                 BIGINT       PK, 자동 증가
--   occurred_at        TIMESTAMPTZ  기록 시각
--   client_ip          INET         복원된 실제 클라이언트 IP. IPv4/IPv6 모두
--   original_filename  TEXT         원본 파일명. 제어문자는 앱이 이스케이프해 저장
--   size_bytes         BIGINT       파일 크기
--   result             VARCHAR(10)  ALLOWED | BLOCKED | ERROR | PENDING
--   reason_code        VARCHAR(40)  실패 사유
--   matched_extension  VARCHAR(20)  차단에 걸린 확장자 (정규화된 형태)
--   note               VARCHAR(40)  차단하지 않았으나 관측된 신호
--   stored_key         TEXT         오브젝트 스토리지 키. yyyy/MM/dd/{UUID}
--   file_id            UUID         클라이언트에 노출되는 파일 식별자. 다운로드가 이 값으로 조회한다
--   deleted_at         TIMESTAMPTZ  객체를 지운 시각. 행은 남는다 (NULL = 살아 있음)
--
-- ★ 두 단계 기록 (PENDING)
--
--   Postgres 와 MinIO 에 걸친 원자성은 2PC 없이 성립하지 않는다. 대신 순서를 뒤집어
--   가장 흔한 실패에서 찌꺼기가 남지 않게 만든다.
--
--     1) INSERT (PENDING, stored_key) 커밋   <- DB 장애면 여기서 끝. MinIO 미접촉
--     2) MinIO PUT                           <- 실패면 UPDATE(ERROR)
--     3) UPDATE (ALLOWED) 커밋               <- 실패면 보상 삭제 시도
--
--   보장하는 것
--     - 참조 무결성: 행이 없으면 객체는 도달 불가능, ALLOWED 면 객체는 반드시 존재
--     - DB 장애 시 찌꺼기 0
--     - 잔여물은 항상 PENDING 으로 남아 탐지 가능

CREATE TABLE upload_audit (
    id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ★ INET 이어야 한다. IPv6 는 같은 주소의 표기가 여러 가지여서 TEXT 로 두면
    --   한 주소가 여러 값으로 갈라지고, IP 별 집계가 불가능해진다.
    --   INET 은 저장 시 정규화하고 형식이 깨진 값을 거부하며 서브넷 연산을 지원한다.
    client_ip          INET,

    original_filename  TEXT        NOT NULL,
    size_bytes         BIGINT,
    result             VARCHAR(10) NOT NULL,
    reason_code        VARCHAR(40),
    matched_extension  VARCHAR(20),
    note               VARCHAR(40),
    stored_key         TEXT,

    -- 클라이언트에 노출되는 식별자. 순차 id 를 내보내지 않으므로 열거할 수 없다.
    -- stored_key 문자열에서 UUID 를 다시 파싱하면 LIKE 조회가 되어 인덱스를 타지 못한다.
    file_id            UUID,

    -- 객체를 지운 시각. 행은 지우지 않는다 — 삭제도 일어난 일이고, 행을 지우면
    -- "무엇이 왜 올라갔는가" 를 함께 잃는다. NULL 이면 살아 있는 파일이다.
    deleted_at         TIMESTAMPTZ,

    CONSTRAINT ck_upload_audit_result CHECK (
        result IN ('ALLOWED', 'BLOCKED', 'ERROR', 'PENDING')
    ),

    -- 상태와 저장 키의 정합. CHECK 는 3값 논리라 IS NULL / IS NOT NULL 을 명시한다.
    CONSTRAINT ck_upload_audit_stored_key CHECK (
        (result IN ('ALLOWED', 'PENDING') AND stored_key IS NOT NULL)
        OR (result = 'BLOCKED' AND stored_key IS NULL)
        OR result = 'ERROR'
    ),

    -- 실패는 이유 없이 기록될 수 없다.
    CONSTRAINT ck_upload_audit_reason CHECK (
        result NOT IN ('BLOCKED', 'ERROR') OR reason_code IS NOT NULL
    ),

    -- blocked_extension.name 과 글자 그대로 같은 규칙.
    CONSTRAINT ck_upload_audit_extension_format CHECK (
        matched_extension IS NULL OR matched_extension ~ '^[a-z0-9]{1,20}$'
    ),

    CONSTRAINT ck_upload_audit_size CHECK (size_bytes IS NULL OR size_bytes >= 0),

    -- 상태와 식별자의 정합. stored_key 와 같은 모양이다.
    CONSTRAINT ck_upload_audit_result_file_id CHECK (
        (result IN ('ALLOWED', 'PENDING') AND file_id IS NOT NULL)
        OR (result = 'BLOCKED' AND file_id IS NULL)
        OR result = 'ERROR'
    ),

    -- 키는 있는데 식별자가 없는 행을 막는다. 객체는 저장됐을 수 있는데 아무도 지목할 수 없다.
    CONSTRAINT ck_upload_audit_stored_key_file_id CHECK (
        stored_key IS NULL OR file_id IS NOT NULL
    ),

    CONSTRAINT uq_upload_audit_stored_key UNIQUE (stored_key),

    -- 지운 적 없는 것을 지웠다고 적을 수 없다. 객체가 존재하는 것이 보증된 상태는 ALLOWED 뿐이다.
    CONSTRAINT ck_upload_audit_deleted_at CHECK (
        deleted_at IS NULL OR result = 'ALLOWED'
    ),

    CONSTRAINT uq_upload_audit_file_id UNIQUE (file_id)
);

CREATE INDEX idx_upload_audit_occurred_at ON upload_audit (occurred_at DESC);

-- 미완료 기록만 훑는 부분 인덱스. 전체의 극소수라 작게 유지된다.
CREATE INDEX idx_upload_audit_pending ON upload_audit (occurred_at)
    WHERE result = 'PENDING';

-- 목록 조회(GET /api/files)가 타는 부분 인덱스. 보여줄 행만 담는다 —
-- 차단 기록이 아무리 쌓여도 목록 조회 비용이 그만큼 늘지 않는다.
CREATE INDEX idx_upload_audit_visible ON upload_audit (occurred_at DESC)
    WHERE result = 'ALLOWED' AND deleted_at IS NULL;

-- 기록은 고쳐 쓸 수 없다.
--
-- 감사 기록의 가치는 "고쳐지지 않았다" 는 신뢰에서 나온다. 사실을 담은 컬럼은 잠그고,
-- 두 단계 프로토콜이 요구하는 result 전이(PENDING -> ALLOWED|ERROR)만 열어둔다.
--
-- DELETE 는 막지 않는다. 보존 기간 정책은 운영의 문제이고, 삭제까지 막으면 테이블이
-- 무한히 자란다. 여기서 지키려는 것은 "남아 있는 기록이 사실인가" 이다.
CREATE OR REPLACE FUNCTION extguard_protect_upload_audit() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
       OR NEW.client_ip IS DISTINCT FROM OLD.client_ip
       OR NEW.original_filename IS DISTINCT FROM OLD.original_filename
       OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
       OR NEW.matched_extension IS DISTINCT FROM OLD.matched_extension
       OR NEW.note IS DISTINCT FROM OLD.note
       OR NEW.stored_key IS DISTINCT FROM OLD.stored_key
       OR NEW.file_id IS DISTINCT FROM OLD.file_id THEN
        RAISE EXCEPTION 'audit record cannot change: only result may transition (id=%)', OLD.id;
    END IF;

    IF NEW.result IS DISTINCT FROM OLD.result THEN
        IF OLD.result <> 'PENDING' OR NEW.result NOT IN ('ALLOWED', 'ERROR') THEN
            RAISE EXCEPTION 'audit record cannot change result: % -> % (id=%)',
                OLD.result, NEW.result, OLD.id;
        END IF;
    END IF;

    -- reason_code 는 PENDING -> ERROR 전이의 일부일 때만 바뀔 수 있다.
    --
    -- 이 컬럼을 잠그지 않으면 확정된 기록의 "왜" 를 나중에 고쳐 쓸 수 있다.
    -- 하필 사건을 설명하는 바로 그 필드다 — 여기가 열려 있으면
    -- 나머지를 아무리 잠가도 기록을 믿을 수 없다.
    IF NEW.reason_code IS DISTINCT FROM OLD.reason_code
       AND NOT (OLD.result = 'PENDING' AND NEW.result = 'ERROR') THEN
        RAISE EXCEPTION 'audit record cannot change reason_code (id=%)', OLD.id;
    END IF;

    -- deleted_at 은 NULL -> 시각으로 한 번만. 시각 -> 다른 시각도, 시각 -> NULL 도 막는다.
    -- 되돌릴 수 있으면 "지웠다" 는 사실을 없던 일로 만들 수 있고, 그것은 기록 조작이다.
    IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'audit record cannot change deleted_at once set (id=%)', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_upload_audit_protect
    BEFORE UPDATE ON upload_audit
    FOR EACH ROW EXECUTE FUNCTION extguard_protect_upload_audit();


-- -----------------------------------------------------------------------------
-- 커스텀 확장자 슬롯 할당 참고 쿼리
-- -----------------------------------------------------------------------------
-- 추가 시 빈 슬롯 하나를 찾는다. 없으면 200개가 찼다는 뜻이다.
--
-- ★ 이 조회만으로는 동시성 안전하지 않다.
--   READ COMMITTED 에서 두 트랜잭션이 같은 빈 슬롯을 보고, 한쪽이 unique_violation(23505)으로
--   실패한다. 200개를 넘기지는 못하지만 여유 슬롯이 있는데도 요청이 실패한다.
--   따라서 애플리케이션은 pg_advisory_xact_lock 으로 정책 쓰기를 직렬화한다.
--   제약은 정합성을, advisory lock 은 불필요한 충돌 회피를 담당한다.
--
--   SELECT s FROM generate_series(1, 200) s
--   LEFT JOIN blocked_extension b ON b.custom_slot = s
--   WHERE b.id IS NULL
--   ORDER BY s LIMIT 1;

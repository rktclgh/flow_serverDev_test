-- 업로드 감사 기록
--
-- 이 서비스의 존재 이유는 "무엇이 왜 차단됐는가" 를 답하는 것이다.
-- 따라서 기록은 결과물이 아니라 <b>기능 그 자체</b>이며, 앞뒤가 맞지 않는 행이 하나라도
-- 들어가면 전체의 신뢰가 떨어진다. blocked_extension 과 같은 원칙으로 DB 가 보증한다.
--
-- ★ 두 단계 기록 (PENDING)
--
--   Postgres 와 MinIO 에 걸친 원자성은 2PC 없이 성립하지 않는다. 대신 순서를 뒤집어
--   "가장 흔한 실패에서 찌꺼기가 남지 않게" 만든다.
--
--     1) INSERT (PENDING, stored_key)  커밋   <- DB 장애면 여기서 끝. MinIO 미접촉
--     2) MinIO PUT                            <- 실패면 UPDATE(ERROR)
--     3) UPDATE (ALLOWED)              커밋   <- 실패면 보상 삭제 시도
--
--   이 설계가 보장하는 것은 셋이다.
--     - 참조 무결성: 행이 없으면 객체는 도달 불가능, ALLOWED 면 객체는 반드시 존재
--     - DB 장애 시 찌꺼기 0
--     - 잔여물은 항상 PENDING 으로 남아 탐지 가능 (조용히 새지 않는다)

CREATE TABLE upload_audit (
    id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ★ INET 이어야 한다. TEXT 가 아니다.
    --
    --   IPv6 는 같은 주소를 여러 방식으로 표기할 수 있다.
    --     2001:0db8:0000:0000:0000:0000:0000:0001  ==  2001:db8::1
    --   문자열로 저장하면 한 주소가 여러 값으로 갈라져, IP 별 시도 횟수를 세는 것 자체가
    --   불가능해진다. INET 은 저장 시 정규화하고, 형식이 깨진 값을 거부하며,
    --   서브넷 연산(<<)을 지원한다. NULL 은 주소를 얻지 못한 경우를 위해 허용한다.
    client_ip          INET,

    -- 원본 파일명. 거부된 요청도 기록하므로 제어문자가 섞인 값이 들어올 수 있다.
    -- 애플리케이션이 저장 전에 \uXXXX 로 이스케이프한다. 화면 출력 시 HTML escape 는 별도.
    original_filename  TEXT        NOT NULL,

    size_bytes         BIGINT,

    result             VARCHAR(10) NOT NULL,

    reason_code        VARCHAR(40),

    -- 차단에 걸린 확장자. 정규화된 형태만 들어간다.
    matched_extension  VARCHAR(20),

    -- 차단하지는 않았으나 관측된 신호 (예: SUSPICIOUS_MIDDLE_SEGMENT).
    -- 차단 정책과 관측을 분리하기 위한 컬럼이다.
    note               VARCHAR(40),

    -- 오브젝트 스토리지의 키. yyyy/MM/dd/{UUID} 형태이며 확장자를 포함하지 않는다.
    stored_key         TEXT,

    CONSTRAINT ck_upload_audit_result CHECK (
        result IN ('ALLOWED', 'BLOCKED', 'ERROR', 'PENDING')
    ),

    -- 상태와 저장 키가 앞뒤가 맞아야 한다.
    --
    --   ALLOWED / PENDING : 키가 정해진 뒤에만 이 상태가 된다 -> NOT NULL
    --   BLOCKED           : 저장하지 않았다 -> NULL
    --   ERROR             : 저장 전에 실패했을 수도, 후에 실패했을 수도 있다 -> 둘 다 허용
    --
    -- CHECK 는 3값 논리라 결과가 NULL 이면 통과로 취급된다. IS NULL / IS NOT NULL 을
    -- 명시해 NULL 이 판정을 빠져나가지 못하게 한다.
    CONSTRAINT ck_upload_audit_stored_key CHECK (
        (result IN ('ALLOWED', 'PENDING') AND stored_key IS NOT NULL)
        OR (result = 'BLOCKED' AND stored_key IS NULL)
        OR result = 'ERROR'
    ),

    -- 실패는 이유 없이 기록될 수 없다. 사유 없는 차단 기록은 나중에 아무것도 설명하지 못한다.
    CONSTRAINT ck_upload_audit_reason CHECK (
        result NOT IN ('BLOCKED', 'ERROR') OR reason_code IS NOT NULL
    ),

    -- ExtensionNormalizer 통과값과 같은 규칙. blocked_extension.name 과 글자 그대로 같아야 한다.
    CONSTRAINT ck_upload_audit_extension_format CHECK (
        matched_extension IS NULL OR matched_extension ~ '^[a-z0-9]{1,20}$'
    ),

    CONSTRAINT ck_upload_audit_size CHECK (size_bytes IS NULL OR size_bytes >= 0),

    -- 한 객체를 두 기록이 가리키면 어느 쪽이 사실인지 알 수 없다.
    CONSTRAINT uq_upload_audit_stored_key UNIQUE (stored_key)
);

-- 최근 기록 조회가 기본 접근 패턴이다.
CREATE INDEX idx_upload_audit_occurred_at ON upload_audit (occurred_at DESC);

-- 미완료 기록만 훑는 부분 인덱스. 전체의 극소수라 인덱스가 작게 유지된다.
CREATE INDEX idx_upload_audit_pending ON upload_audit (occurred_at)
    WHERE result = 'PENDING';

-- -----------------------------------------------------------------------------
-- 기록은 고쳐 쓸 수 없다
-- -----------------------------------------------------------------------------
--
-- 감사 기록의 가치는 "고쳐지지 않았다" 는 신뢰에서 나온다. 사실을 담은 컬럼은 잠그고,
-- 두 단계 프로토콜이 요구하는 result 전이만 열어둔다.
--
-- DELETE 는 막지 않는다. 보존 기간 정책은 운영의 문제이고, 삭제까지 막으면
-- 테이블이 무한히 자란다. 여기서 지키려는 것은 "남아 있는 기록이 사실인가" 이다.

CREATE OR REPLACE FUNCTION extguard_protect_upload_audit() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
       OR NEW.client_ip IS DISTINCT FROM OLD.client_ip
       OR NEW.original_filename IS DISTINCT FROM OLD.original_filename
       OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
       OR NEW.matched_extension IS DISTINCT FROM OLD.matched_extension
       OR NEW.note IS DISTINCT FROM OLD.note
       OR NEW.stored_key IS DISTINCT FROM OLD.stored_key THEN
        RAISE EXCEPTION 'audit record cannot change: only result may transition (id=%)', OLD.id;
    END IF;

    -- 두 단계 기록 프로토콜. PENDING 만 확정 상태로 갈 수 있다.
    IF NEW.result IS DISTINCT FROM OLD.result THEN
        IF OLD.result <> 'PENDING' OR NEW.result NOT IN ('ALLOWED', 'ERROR') THEN
            RAISE EXCEPTION 'audit record cannot change result: % -> % (id=%)',
                OLD.result, NEW.result, OLD.id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_upload_audit_protect
BEFORE UPDATE ON upload_audit
FOR EACH ROW EXECUTE FUNCTION extguard_protect_upload_audit();

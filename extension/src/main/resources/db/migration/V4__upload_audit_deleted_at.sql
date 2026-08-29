-- upload_audit.deleted_at — 객체는 지우고 기록은 남긴다
--
-- 왜 행을 지우지 않는가
--
--   업로드된 파일을 지울 수단이 필요하지만, 감사 행을 DELETE 하면 "무엇이 왜 올라갔는가"
--   를 함께 잃는다. 그것이 이 서비스의 존재 이유이므로 삭제는 <b>객체</b>에만 적용하고,
--   기록에는 "지웠다" 는 사실을 하나 더 적는다. 삭제 역시 일어난 일이다.
--
--   V2 의 주석은 "DELETE 는 막지 않는다 — 보존 기간 정책은 운영의 문제" 라고 썼다.
--   그것과 모순되지 않는다. 여기서 막는 것은 <b>사용자 요청에 의한 삭제</b>가 기록을
--   지우는 경로이고, 운영이 보존 기간에 따라 오래된 행을 걷어내는 것은 여전히 열려 있다.
--
-- result 를 건드리지 않는 이유
--
--   'DELETED' 같은 상태를 새로 만들면 ALLOWED -> DELETED 전이를 허용해야 하고,
--   그 순간 "확정된 result 는 다시 바뀌지 않는다" 는 V2 의 불변성이 무너진다.
--   컬럼을 따로 두면 기존 규칙을 한 글자도 건드리지 않고 새 사실만 더할 수 있다.
--   ck_upload_audit_stored_key · ck_upload_audit_result_file_id 도 그대로 성립한다.

-- 1) 컬럼. nullable — 삭제되지 않은 것이 정상 상태다.
ALTER TABLE upload_audit ADD COLUMN deleted_at TIMESTAMPTZ;

-- 2) 지운 적 없는 것을 지웠다고 적을 수 없다.
--
--    BLOCKED 는 저장한 적이 없어 지울 객체가 없고, PENDING 은 아직 결말이 아니며
--    (정리는 스위퍼의 몫이다), ERROR 는 이미 다른 결말이다. 지울 수 있는 것은
--    "저장이 끝났고 객체가 존재한다" 가 보증된 ALLOWED 뿐이다.
--
--    ★ CHECK 는 3값 논리다. deleted_at IS NULL 을 앞에 명시하지 않으면 NULL 인 행에서
--      결과가 NULL 이 되어 통과로 취급되는데, 여기서는 그것이 곧 원하는 동작이라
--      의도를 드러내기 위해 명시한다.
ALTER TABLE upload_audit
    ADD CONSTRAINT ck_upload_audit_deleted_at CHECK (
        deleted_at IS NULL OR result = 'ALLOWED'
    );

-- 3) 목록 질의를 겨냥한 부분 인덱스.
--
--    GET /api/files 는 result = 'ALLOWED' AND deleted_at IS NULL 을 occurred_at DESC 로
--    훑는다. 전체 인덱스(idx_upload_audit_occurred_at)로도 답은 나오지만 차단·삭제된 행까지
--    지나며 걸러내야 한다. 부분 인덱스는 <b>보여줄 행만</b> 담아, 차단 기록이 아무리 쌓여도
--    목록 조회 비용이 그만큼 늘지 않는다.
CREATE INDEX idx_upload_audit_visible ON upload_audit (occurred_at DESC)
    WHERE result = 'ALLOWED' AND deleted_at IS NULL;

-- 4) ★ 트리거 확장 — deleted_at 은 NULL -> 시각으로 <b>한 번만</b> 바뀐다.
--
--    새 컬럼을 불변 목록에 그냥 더할 수는 없다. 그러면 삭제 자체가 불가능해진다.
--    그렇다고 열어두면 시각을 고쳐 쓰거나 NULL 로 되돌릴 수 있는데, 그것은 "지웠다" 는
--    사실을 없던 일로 만드는 것이다 — 기록 조작이다. 그래서 <b>단방향 1회</b> 전이만 연다.
--
--    file_id 를 빠뜨렸다면 새 컬럼만 불변성 밖으로 샜을 것이다(V3 §5). 같은 함정이
--    여기에도 있다. 컬럼을 더할 때마다 이 함수를 함께 고쳐야 한다.
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

    -- 두 단계 기록 프로토콜. PENDING 만 확정 상태로 갈 수 있다.
    IF NEW.result IS DISTINCT FROM OLD.result THEN
        IF OLD.result <> 'PENDING' OR NEW.result NOT IN ('ALLOWED', 'ERROR') THEN
            RAISE EXCEPTION 'audit record cannot change result: % -> % (id=%)',
                OLD.result, NEW.result, OLD.id;
        END IF;
    END IF;

    -- reason_code 는 PENDING -> ERROR 전이의 일부일 때만 바뀔 수 있다.
    IF NEW.reason_code IS DISTINCT FROM OLD.reason_code
       AND NOT (OLD.result = 'PENDING' AND NEW.result = 'ERROR') THEN
        RAISE EXCEPTION 'audit record cannot change reason_code (id=%)', OLD.id;
    END IF;

    -- ★ deleted_at 은 NULL -> 시각으로 한 번만. 시각 -> 다른 시각도, 시각 -> NULL 도 막는다.
    --
    --   두 금지를 한 조건으로 적는다. "이미 값이 있는데 달라지려 한다" 가 둘의 공통 형태다.
    --   IS DISTINCT FROM 을 쓰는 이유는 = 비교가 NULL 앞에서 NULL 을 내놓아
    --   시각 -> NULL 을 통과시키기 때문이다.
    IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'audit record cannot change deleted_at once set (id=%)', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

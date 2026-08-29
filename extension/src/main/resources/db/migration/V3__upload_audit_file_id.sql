-- upload_audit.file_id — 클라이언트에 노출되는 파일 식별자
--
-- 왜 필요한가
--
--   업로드 응답의 fileId 와 다운로드 경로 GET /api/files/{fileId}/content 가 이 값을 쓴다.
--   지금까지는 UUID 가 stored_key 문자열 끝에만 있었다. 그 상태로 조회하려면
--   stored_key LIKE '%/' || :uuid 가 되어 인덱스를 타지 못하고, 애초에 키의 형태(날짜 프리픽스)
--   가 조회 조건이 되어버린다. 값을 갈라 두면 두 문제가 함께 사라진다.
--
--   순차 id 를 쓰지 않는 이유는 SPEC §7.5 와 같다 — 열거 가능한 식별자를 공개하지 않는다.
--
-- 순서가 있다 (SPEC §21.8)
--
--   이 테이블은 지금 어느 환경에서도 비어 있다. 그래도 행이 있는 상태에서 안전해야 한다 —
--   "지금은 비어 있으니까" 는 다음 배포에서 사실이 아니게 되고, 그때 이 파일은 이미 적용된
--   뒤라 고칠 수 없다(Flyway 체크섬).

-- 1) 먼저 nullable 로 붙인다. NOT NULL 로 시작하면 기존 행이 있는 순간 실패한다.
ALTER TABLE upload_audit ADD COLUMN file_id UUID;

-- 2) 백필.
--
--    ★ stored_key 가 NULL 인 행은 건드리지 않는다.
--      BLOCKED 는 저장한 적이 없고, 저장 전에 실패한 ERROR 도 마찬가지다. 그 행들에까지
--      UUID 를 만들면 4)의 "BLOCKED -> NULL" 검증과 정면으로 충돌해 이 마이그레이션이
--      배포 도중에 멈춘다.
--
--    키 끝 36자가 UUID 모양이면 그것을 그대로 쓴다. 새로 만들면 이미 저장된 객체와 행이
--    가리키는 식별자가 어긋나 다운로드가 조용히 404 가 된다.
--
--    ★ 판별은 문자 집합이 아니라 자리까지 본다.
--
--      '^[0-9a-f-]{36}$' 로 쓰면 하이픈 36개도, 하이픈 없는 16진수 36자도 통과한다.
--      그 값에 ::uuid 를 걸면 캐스팅이 실패하고 이 마이그레이션이 배포 도중 멈춘다.
--      V2 는 stored_key 에 임의의 문자열을 허용하므로 그런 행은 옛 스키마에서 완전히
--      정상인 데이터다 — 없을 리 없다고 가정할 근거가 없다.
--
--      형식이 어긋나면 새 UUID 를 만드는 분기가 아래에 이미 있다. 판별만 정확해지면
--      결과는 안전하다 — 막을 것을 늘리는 게 아니라 이미 있는 안전한 길로 보내는 것이다.
--      (외부 리뷰 Codex P1)
UPDATE upload_audit
   SET file_id = CASE
       WHEN stored_key IS NULL THEN NULL
       WHEN right(stored_key, 36) ~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN right(stored_key, 36)::uuid
       ELSE gen_random_uuid()
   END
 WHERE file_id IS NULL;

-- 3) 검증. 여기서 멈추면 4)의 제약은 걸리지 않는다.
--
--    제약을 걸어보고 실패를 읽는 것과 무엇이 왜 어긋났는지 말해주는 것은 다르다.
--    운영 데이터에서 실패했을 때 읽어야 하는 것은 "제약 위반" 이 아니라 원인이다.
DO $$
DECLARE
    duplicates bigint;
    mismatched bigint;
BEGIN
    SELECT count(*) INTO duplicates FROM (
        SELECT file_id FROM upload_audit
         WHERE file_id IS NOT NULL
         GROUP BY file_id HAVING count(*) > 1
    ) d;
    IF duplicates > 0 THEN
        RAISE EXCEPTION 'file_id 백필 결과에 중복이 %건 있습니다. UNIQUE 를 걸 수 없습니다', duplicates;
    END IF;

    SELECT count(*) INTO mismatched FROM upload_audit
     WHERE (result IN ('ALLOWED', 'PENDING') AND file_id IS NULL)
        OR (result = 'BLOCKED' AND file_id IS NOT NULL)
        OR (stored_key IS NOT NULL AND file_id IS NULL);
    IF mismatched > 0 THEN
        RAISE EXCEPTION '상태별 file_id 규칙을 어긴 행이 %건 있습니다', mismatched;
    END IF;
END $$;

-- 4) 제약. stored_key 와 같은 모양으로 맞춘다.
--
--    ★ 이름을 형제 규칙 바로 뒤에 붙였다(result -> result_file_id, stored_key -> stored_key_file_id).
--      Postgres 는 한 행이 여러 CHECK 를 어길 때 이름이 앞서는 것 하나만 보고한다.
--      그래서 이름을 아무렇게나 지으면 기존 위반의 오류 메시지가 새 제약 이름으로 바뀌고,
--      "무엇을 어겼는가" 를 읽던 사람과 테스트가 함께 어긋난다.
ALTER TABLE upload_audit
    ADD CONSTRAINT uq_upload_audit_file_id UNIQUE (file_id);

ALTER TABLE upload_audit
    ADD CONSTRAINT ck_upload_audit_result_file_id CHECK (
        (result IN ('ALLOWED', 'PENDING') AND file_id IS NOT NULL)
        OR (result = 'BLOCKED' AND file_id IS NULL)
        OR result = 'ERROR'
    );

-- 키는 있는데 식별자가 없는 ERROR 행을 막는다. 그런 행은 객체가 저장됐을 수 있는데
-- 아무도 그것을 지목할 수 없다 — 스위퍼는 stored_key 로 지우지만, 사람이 조회할 길이 없다.
ALTER TABLE upload_audit
    ADD CONSTRAINT ck_upload_audit_stored_key_file_id CHECK (
        stored_key IS NULL OR file_id IS NOT NULL
    );

-- 5) ★ 트리거의 불변 컬럼 목록에 file_id 를 더한다.
--
--    이것을 빠뜨리면 새 컬럼만 불변성 보증 밖으로 샌다. 나머지를 아무리 잠가도
--    "이 파일이 그 파일이다" 를 나중에 고쳐 쓸 수 있으면 기록을 믿을 수 없다.
--    reason_code 가 정확히 그렇게 빠져 있다가 외부 리뷰에서 잡혔다. 같은 실수를 반복하지 않는다.
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

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

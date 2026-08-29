# ExtGuard — 파일 확장자 차단 업로드

파일 업로드 시 **확장자 기반 차단 정책**을 관리하고, 실제 업로드에 그 정책을 강제하는 서비스입니다.

**배포 주소 — https://flowtest.rktclgh.site** (별도 계정 없이 접속 가능합니다)

정책 화면과 업로드 화면이 한 페이지에 있습니다. 고정 확장자를 체크하면 그 즉시 업로드가 막히고,
막힌 이유가 화면에 그대로 나옵니다.

---

## 실행 방법

Docker 만 있으면 됩니다. DB·오브젝트 스토리지·프론트엔드 빌드가 모두 구성 안에 들어 있습니다.

```bash
git clone https://github.com/rktclgh/flow_serverDev_test.git
cd flow_serverDev_test
docker compose up --build
```

빌드가 끝나면 **http://localhost:8080** 으로 접속합니다. 첫 실행은 npm 의존성 설치와 Gradle
빌드를 함께 수행하므로 네트워크 상태에 따라 몇 분 걸립니다. 두 번째부터는 레이어 캐시가 걸려
수십 초 안에 뜹니다.

정리는 아래 한 줄입니다. `-v` 를 붙여야 DB·스토리지 볼륨까지 함께 지워집니다.

```bash
docker compose down -v
```

### 뜨는 것

| 컨테이너 | 이미지 | 호스트 포트 | 역할 |
|---|---|---|---|
| `app` | 이 저장소에서 빌드 | 8080 | Spring Boot + React 정적 번들 |
| `db` | `postgres:17-alpine` | 15432 | 정책·감사 기록 |
| `minio` | MinIO (S3 호환) | 19100 / 19101 | 업로드된 파일의 실제 저장소 |
| `minio-init` | `minio/mc` | — | 버킷 생성 후 종료되는 일회성 컨테이너 |

포트가 겹치면 `.env` 로 바꿉니다. `cp .env.example .env` 후 값을 고치면 됩니다.
`.env` 없이도 모든 값에 기본값이 있어 그대로 실행됩니다.

### 관리 토큰

정책을 **바꾸는** 요청(고정 확장자 토글, 커스텀 추가·삭제)과 **파일 삭제**는 관리 토큰을 요구합니다.
조회·업로드·다운로드는 공개입니다.

로컬에서는 `docker-compose.yml` 의 기본 토큰이 그대로 쓰입니다.

```text
local-demo-token-do-not-use-in-production
```

화면 상단 헤더의 **관리 토큰** 칸에 붙여넣고 저장하면 정책을 수정할 수 있습니다.
토큰은 `sessionStorage` 에만 있고 탭을 닫으면 사라집니다.

운영에서는 반드시 교체합니다. 32자 미만이면 **애플리케이션이 기동에 실패**합니다 —
만료도 잠금도 없는 토큰이라 짧으면 방어가 되지 않기 때문에, 설정 실수를 첫 요청이 아니라
배포 시점에 드러나게 만든 것입니다.

```bash
APP_ADMIN_TOKEN=$(openssl rand -hex 32) docker compose up -d
```

### 프론트엔드만 따로 띄우기 (선택)

화면을 수정할 때만 필요합니다. 백엔드는 위의 `docker compose` 로 띄워둔 상태를 전제로 하며,
Vite 개발 서버가 `/api` 와 `/health` 를 8080 으로 프록시합니다.

```bash
cd web
npm ci
npm run dev
```

---

## 테이블 스키마

Flyway 마이그레이션(`extension/src/main/resources/db/migration/`)이 단일 진실 원천입니다.
전체 정본은 **[`extension/src/main/resources/db/schema.sql`](extension/src/main/resources/db/schema.sql)**
에 한 파일로 정리해 두었습니다(실행되지는 않는 문서이고, 마이그레이션과 어긋나지 않는지는
`SchemaDriftTest` 가 두 스키마를 실제로 만들어 대조합니다).

설계 원칙은 하나입니다 — **도메인 불변식을 애플리케이션이 아니라 DB 가 지킨다.**
앱의 검사는 사용자에게 친절한 오류를 주기 위한 것이고, 정합성 보증은 제약과 트리거가 합니다.
API 를 우회해도 지켜져야 하기 때문입니다.

### `blocked_extension` — 차단 정책

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | `BIGINT` | PK, `GENERATED ALWAYS AS IDENTITY` | |
| `name` | `VARCHAR(20)` | `NOT NULL`, `UNIQUE` | 정규화된 확장자. 앞의 점 없이 소문자 영숫자 |
| `type` | `VARCHAR(10)` | `NOT NULL`, `CHECK IN ('FIXED','CUSTOM')` | 고정 7개 / 사용자 추가 |
| `is_blocked` | `BOOLEAN` | `NOT NULL DEFAULT FALSE` | 고정 확장자의 체크 상태 |
| `custom_slot` | `SMALLINT` | `UNIQUE`, `CHECK 1..200` | 커스텀 200개 상한을 선언적으로 보증 |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | 트리거로 자동 갱신 |

**인덱스** — `uq_blocked_extension_name`, `uq_blocked_extension_slot`, `idx_blocked_extension_type`.
최대 207행(고정 7 + 커스텀 200)이라 전체 조회는 seq scan 이 더 빠릅니다. 인덱스를 더 만들지 않은 것이
성능 판단의 결과입니다.

**제약이 지키는 규칙**

- `UNIQUE (name)` — 중복 추가 방지. 커스텀에 `exe` 를 넣는 경우도 이 하나로 함께 막힙니다.
  앱의 `exists()` 체크는 동시 요청에서 뚫리므로 실제 방어선은 이쪽입니다.
- `ck_fixed_names` — `FIXED` 의 이름을 `bat cmd com cpl exe scr js` 로 못박습니다.
  이것이 없으면 `type='FIXED'` 로 임의의 행을 무한히 추가할 수 있습니다.
- `ck_custom_always_blocked` — 커스텀은 "행의 존재 = 차단". 토글 대상은 고정뿐입니다.
- `ck_blocked_extension_format` — `^[a-z0-9]{1,20}$`. 앱 정규화기(`ExtensionNormalizer`)의 최종 보증입니다.
- `ck_custom_slot` — 커스텀은 슬롯이 반드시 있어야 합니다. `IS NOT NULL` 을 명시한 이유는
  SQL 의 CHECK 가 3값 논리라, 없으면 슬롯이 NULL 일 때 판정이 NULL 이 되어 통과해버리고
  200개 상한이 무력화되기 때문입니다.

**트리거** — 고정 확장자 삭제 금지, 고정 행 변조 금지(바뀔 수 있는 것은 `is_blocked` 뿐),
`TRUNCATE` 금지, `updated_at` 자동 갱신.

### `upload_audit` — 업로드 감사 기록

이 서비스의 존재 이유는 "무엇이 왜 차단됐는가" 를 답하는 것이라, 기록은 부산물이 아니라 기능 자체입니다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | `BIGINT` | PK | |
| `occurred_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | |
| `client_ip` | `INET` | | 요청자 IP |
| `original_filename` | `TEXT` | `NOT NULL` | 원본 파일명(제어문자는 저장 전 이스케이프) |
| `size_bytes` | `BIGINT` | `CHECK >= 0` | |
| `result` | `VARCHAR(10)` | `CHECK IN ('ALLOWED','BLOCKED','ERROR','PENDING')` | 판정 |
| `reason_code` | `VARCHAR(40)` | 실패면 `NOT NULL` | 거부 사유 |
| `matched_extension` | `VARCHAR(20)` | `CHECK ^[a-z0-9]{1,20}$` | 차단에 걸린 확장자 |
| `note` | `VARCHAR(40)` | | 차단하지 않았으나 관측된 신호 |
| `stored_key` | `TEXT` | `UNIQUE` | 스토리지 키 `yyyy/MM/dd/{UUID}` |
| `file_id` | `UUID` | `UNIQUE` | 클라이언트에 노출되는 식별자 |
| `deleted_at` | `TIMESTAMPTZ` | `CHECK` — `ALLOWED` 만 가능 | 객체를 지운 시각 |

**인덱스** — `idx_upload_audit_occurred_at`(최근순 조회), `idx_upload_audit_pending`(미완료 행만 담는
부분 인덱스), `idx_upload_audit_visible`(`ALLOWED AND deleted_at IS NULL` 만 담는 목록 전용 부분 인덱스).
차단 기록이 아무리 쌓여도 목록 조회 비용이 그만큼 늘지 않습니다.

**`INET` 인 이유** — IPv6 는 같은 주소를 여러 방식으로 표기할 수 있습니다
(`2001:0db8:0000:...:0001` 과 `2001:db8::1`). 문자열로 저장하면 한 주소가 여러 값으로 갈라져
IP 별 시도 횟수를 세는 것 자체가 불가능해집니다. `INET` 은 저장 시 정규화하고 형식이 깨진 값을 거부합니다.

**두 단계 기록** — Postgres 와 MinIO 에 걸친 원자성은 2PC 없이 성립하지 않습니다. 대신 순서를
뒤집어 "가장 흔한 실패에서 찌꺼기가 남지 않게" 만들었습니다.

```text
① INSERT (PENDING, stored_key) 커밋   → DB 장애면 여기서 끝. MinIO 미접촉
② MinIO PUT                          → 실패면 UPDATE(ERROR)
③ UPDATE (ALLOWED) 커밋               → 실패면 PENDING 유지 (지우지 않는다)
```

`PENDING` 은 "실패" 가 아니라 **"모른다"** 입니다. 잔여물은 항상 `PENDING` 으로 남아 탐지 가능하고,
스케줄러(`PendingUploadSweeper`)가 임계 시간이 지난 것만 걷어냅니다. 이때도 조건부
`UPDATE ... WHERE result='PENDING'` 으로 **소유권을 먼저 얻은 뒤** 객체를 지웁니다 —
그 사이 업로드가 `ALLOWED` 로 확정됐다면 갱신 행 수가 0이 되어 객체를 건드리지 않습니다.

**기록은 고쳐 쓸 수 없습니다.** 사실을 담은 컬럼은 트리거가 전부 잠그고, 두 단계 프로토콜이
요구하는 `PENDING → ALLOWED|ERROR` 전이와 `deleted_at` 의 단방향 1회 설정만 열려 있습니다.
삭제도 마찬가지입니다 — 객체만 지우고 행은 남깁니다. 삭제 역시 일어난 일이기 때문입니다.

---

## API

| Method | 경로 | 토큰 | 설명 |
|---|---|---|---|
| `GET` | `/api/extensions` | | 정책 전체 조회 (고정 7 + 커스텀 목록 + 개수/상한) |
| `PATCH` | `/api/extensions/fixed/{name}` | 필요 | 고정 확장자 체크/해제. 본문 `{"blocked": true}` |
| `POST` | `/api/extensions/custom` | 필요 | 커스텀 추가. 본문 `{"name": "sh"}` → `201` |
| `DELETE` | `/api/extensions/custom/{name}` | 필요 | 커스텀 삭제 → `204` |
| `POST` | `/api/files` | | 업로드. `multipart/form-data`, 파트 이름은 `file` → `201` |
| `GET` | `/api/files` | | 업로드된 파일 목록 |
| `GET` | `/api/files/{fileId}/content` | | 다운로드 |
| `DELETE` | `/api/files/{fileId}` | 필요 | 파일 삭제 → `204` (기록은 남습니다) |
| `DELETE` | `/api/files` | 필요 | 여러 건 삭제. 본문 `{"fileIds": [...]}`, 최대 100건 → `200` |
| `GET` | `/health` | | 헬스체크 |

실패 응답은 형태가 하나입니다. **상태 코드보다 `code` 가 실질입니다** — 상태 코드는 프록시나
서버가 만들어낼 수도 있어 애플리케이션의 판정과 1:1로 대응하지 않기 때문입니다.

```json
{
  "code": "FILE_BLOCKED_EXTENSION",
  "message": "exe 확장자는 업로드가 차단되어 있습니다.",
  "detail": { "blockedExtension": "exe", "policyType": "FIXED" }
}
```

**여러 건 삭제는 `200` 이라도 전부 지워졌다는 뜻이 아닙니다.** 건별 결과를 돌려줍니다.

```json
{ "deleted": ["b005c6eb-…", "b2160de0-…"], "notFound": ["00000000-…"] }
```

목록은 낡을 수 있습니다 — 다른 탭에서 이미 지웠거나 정리 스케줄러가 걷어간 뒤일 수 있습니다.
그 한 건 때문에 요청 전체를 거부하면 사용자는 새로고침하고 다시 고르는 일을 반복하게 됩니다.
그래서 지울 수 있는 것은 지우고 나머지를 알려줍니다. `notFound` 에는 없던 id, 이미 지운 파일,
차단돼 저장된 적 없는 기록이 **한데** 담깁니다. 셋을 나눠 답하면 응답 차이만으로 "그 식별자는
존재하지만 차단됐다" 를 알아낼 수 있습니다.

### 명령줄로 확인하기

```bash
TOKEN=local-demo-token-do-not-use-in-production

# exe 차단을 켠다
curl -X PATCH http://localhost:8080/api/extensions/fixed/exe \
     -H "X-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
     -d '{"blocked": true}'

# 차단된 확장자를 올려본다 → 422 FILE_BLOCKED_EXTENSION
printf 'hello' > sample.exe
curl -i -X POST http://localhost:8080/api/files -F "file=@sample.exe"

# 이름만 바꿔도 통과하지 못한다 → 422 FILE_EXECUTABLE_CONTENT
printf 'MZ\x90\x00' > disguised.txt
curl -i -X POST http://localhost:8080/api/files -F "file=@disguised.txt"
```

---

## 업로드가 거부되는 지점

검사 순서 자체가 계약입니다. 이름이 부적합하면서 동시에 차단 확장자인 파일은 **이름 오류가
이깁니다** — 판정 순서가 흔들리면 같은 파일이 매번 다른 이유로 거부되고, 사용자는 무엇을 고쳐야
할지 알 수 없게 됩니다.

| 순서 | 검사 | 실패 코드 | 상태 |
|---|---|---|---|
| 1 | 파일명 — 빈 이름, 널바이트, 양방향 제어문자, 제어·서식 문자 | `FILE_NAME_INVALID` | 400 |
| 1 | 파일명(경로를 뗀 basename)이 255 코드포인트 초과 | `FILE_NAME_TOO_LONG` | 400 |
| 2 | 0바이트 | `FILE_EMPTY` | 400 |
| 3 | 확장자 없음 | `FILE_EXTENSION_MISSING` | 422 |
| 4 | 차단 목록에 있는 확장자 | `FILE_BLOCKED_EXTENSION` | 422 |
| 5 | 내용이 실행 파일 (`MZ` / ELF / `#!`) | `FILE_EXECUTABLE_CONTENT` | 422 |

3번이 400이 아니라 422인 이유는 요청이 잘못된 것이 아니라 **정책이 거부**한 것이기 때문입니다.
설정(`APP_POLICY_ALLOW_EXTENSIONLESS`)으로 허용할 수 있습니다.

5번은 확장자를 믿지 않는 검사입니다. 파일 크기와 무관하게 선두 몇 바이트만 봅니다.

저장할 때 **원본 파일명을 쓰지 않습니다.** 키는 `yyyy/MM/dd/{UUID}` 이고, 키 생성기는 애초에
파일명을 인자로 받지 않습니다 — 구조적으로 파일명이 키에 들어갈 수 없습니다. 경로 조작도,
실행 가능한 이름으로 저장되는 일도 성립하지 않습니다. 원본 파일명은 감사 기록에만 남고,
다운로드할 때 `Content-Disposition` 으로 되돌려줍니다.

---

## 프로젝트 구조

```text
extension/                     Spring Boot (Java 21, Gradle Kotlin DSL)
  src/main/java/flow/test/serverdev/
    policy/                    확장자 정책 — 정규화, 슬롯 할당, 정책 조회/변경
    upload/                    업로드 파이프라인 — 검증, 저장, 목록/다운로드/삭제
    audit/                     감사 기록과 PENDING 스위퍼
    storage/                   오브젝트 스토리지 추상화 (MinIO 구현)
    common/                    오류 계약, 관리 토큰 필터, 속도 제한, 보안 헤더
  src/main/resources/db/
    migration/                 Flyway V1~V4 — 스키마의 단일 진실 원천
    schema.sql                 전체 스키마 정본 (문서)
  src/test/                    단위 · property · Testcontainers 통합 테스트

web/                           React 19 + TypeScript (Vite)
  src/components/              정책 패널, 업로드 패널, 파일 목록, 토스트
  src/store/uploadQueue.ts     업로드 큐 (zustand) — 순차 실행, 재시도, 취소
  src/api/                     API 클라이언트와 타입

Dockerfile                     web 번들을 Spring static 으로 묶어 단일 컨테이너로
docker-compose.yml             app + db + minio + 버킷 초기화
```

프론트와 백엔드를 한 컨테이너로 묶은 이유는 셋입니다 — CORS 설정이 필요 없고, 리버스 프록시가
upstream 하나만 알면 되며, 프론트/백 배포가 원자적으로 함께 일어납니다.

---

## 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| 백엔드 | Spring Boot 4.0.8 / Java 21 | |
| DB | PostgreSQL 17 | CHECK 정규식·`INET`·plpgsql 트리거를 쓰므로 H2 로 대체 불가 |
| 마이그레이션 | Flyway | `ddl-auto: validate` 고정. `update` 사용 금지 |
| 파일 저장 | MinIO (S3 호환) | 앱 컨테이너 파일시스템에 두지 않기 위함 |
| 프론트 | React 19 + TypeScript + Vite | 상태 관리는 업로드 큐에만 zustand |
| 테스트 | JUnit 5 + Testcontainers | 스키마 제약을 실제 Postgres 로 검증 |

---

## 테스트

```bash
cd extension
./gradlew test
```

Testcontainers 가 Postgres 와 MinIO 를 띄우므로 **Docker 가 실행 중**이어야 합니다.
Docker 가 내려가 있으면 통합 테스트가 전부 `ApplicationContext` 로드 실패로 죽는데, 이때의
실패는 코드 문제가 아닙니다.

H2 를 쓰지 않는 이유는 이 프로젝트의 불변식이 대부분 DB 제약과 트리거에 있기 때문입니다.
H2 는 그것들을 지원하지 않아 스키마가 두 벌이 되고, 그러면 테스트가 통과해도 운영에서 지켜지는지
알 수 없게 됩니다.

정상 종료되면 Ryuk 이 컨테이너를 회수하지만, 테스트를 중간에 끊으면 남습니다. 먼저 무엇이
남았는지 봅니다.

```bash
docker ps -a --filter "label=org.testcontainers" --format "{{.ID}}\t{{.Image}}\t{{.Status}}"
```

목록을 확인한 뒤, **더 이상 쓰이지 않는 것만** 골라 지웁니다.

```bash
docker rm -f <위에서 확인한 컨테이너 ID>
```

> 라벨로 한 번에 지우지 마세요. 같은 머신에서 다른 프로젝트의 테스트가 돌고 있으면
> 그 컨테이너도 같은 라벨을 답니다. 남의 테스트를 중간에 끊게 됩니다.
> 같은 이유로 `docker volume prune` 과 `docker system prune -a` 도 쓰지 않습니다.

---

## 함께 제출하는 문서

- **`CONSIDERATIONS.md`** — 기획·보안·예외·운영 관점에서 무엇을 고려했고 왜 그렇게 판단했는지.
  요건에 없었지만 발견해서 막은 것들(인가 우회, 이중 확장자, MIME 스푸핑, 저장 실패 시
  DB·스토리지 정합성 등)도 여기 있습니다.
- **`PROMPT_LOG.md`** — AI 를 어떻게 썼는지의 기록. 그대로 쓴 것 / 고쳐 쓴 것 / 버린 것과
  그 근거, AI 가 틀렸는데 실측으로 잡아낸 사례를 시간순으로 남겼습니다.

두 문서는 편집 이력이 저장소에 남지 않도록 제출물로 별도 전달합니다.

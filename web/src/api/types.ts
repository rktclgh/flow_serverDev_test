/** 서버 공통 에러 응답. (SPEC §7.7) */
export interface ApiError {
  code: string
  message: string
  detail?: Record<string, unknown>
}

export interface FixedExtension {
  name: string
  blocked: boolean
}

export interface CustomExtension {
  name: string
}

export interface PolicyResponse {
  fixed: FixedExtension[]
  custom: CustomExtension[]
  customCount: number
  customLimit: number
}

export interface UploadResponse {
  fileId: string
  originalFilename: string
  size: number
}

/**
 * 서버가 코드를 준 실패.
 *
 * 네트워크 단절과 구분하기 위해 별도 타입으로 둔다 — 전자는 재시도할 값이 없고
 * 후자는 재시도 대상이다. (SPEC §11.3)
 */
export class ApiFailure extends Error {
  readonly status: number
  readonly body: ApiError | null
  readonly retryAfterSeconds?: number

  constructor(status: number, body: ApiError | null, retryAfterSeconds?: number) {
    super(body?.message ?? `요청이 실패했습니다 (HTTP ${status})`)
    this.name = 'ApiFailure'
    this.status = status
    this.body = body
    this.retryAfterSeconds = retryAfterSeconds
  }

  get code(): string | null {
    return this.body?.code ?? null
  }
}

/**
 * 업로드된 파일 한 건. (`GET /api/files`)
 *
 * `uploadedAt` 은 오프셋을 포함한 ISO-8601 문자열이다 — 화면은 사용자의 지역 시간대로 표시한다.
 */
export interface UploadedFile {
  fileId: string
  originalFilename: string
  size: number
  uploadedAt: string
}

export interface FileListResponse {
  files: UploadedFile[]
}

/**
 * 여러 건 삭제의 건별 결과.
 *
 * `notFound` 에는 지울 수 없었던 모든 경우가 들어간다 — 없던 id, 이미 지운 파일,
 * 차단돼 저장된 적 없는 기록. 서버가 셋을 구분해 답하지 않는 것은 의도다.
 */
export interface BulkDeleteResponse {
  deleted: string[]
  notFound: string[]
}

/** 활동 로그 한 줄. 정책 변경과 업로드 판정이 같은 모양으로 내려온다. */
export interface AuditEntry {
  at: string
  kind: 'POLICY' | 'UPLOAD'
  action: string
  target: string
  detail: string | null
  /** 요청자 주소. 관리자가 여럿이 되기 전까지 "누가" 를 대신한다. */
  clientIp: string | null
}

export interface AuditResponse {
  entries: AuditEntry[]
}

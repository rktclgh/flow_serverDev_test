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

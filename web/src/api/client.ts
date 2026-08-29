import { ApiFailure, type ApiError } from './types'

const ADMIN_TOKEN_KEY = 'extguard.adminToken'

/**
 * 관리 토큰은 sessionStorage 에만 둔다. (SPEC §11.1)
 *
 * localStorage 로 두면 탭을 닫아도 남는다. 이 토큰은 정책을 바꿀 수 있는 값이고,
 * 공용 PC 에서 시연할 수 있으므로 세션과 함께 사라지는 편이 맞다.
 */
export function readAdminToken(): string {
  try {
    return sessionStorage.getItem(ADMIN_TOKEN_KEY) ?? ''
  } catch {
    return ''
  }
}

export function writeAdminToken(token: string): void {
  try {
    if (token) sessionStorage.setItem(ADMIN_TOKEN_KEY, token)
    else sessionStorage.removeItem(ADMIN_TOKEN_KEY)
  } catch {
    /* 시크릿 모드 등에서 막힐 수 있다. 토큰이 없으면 관리 기능만 실패한다. */
  }
}

/**
 * 응답 본문을 JSON 으로 읽되 실패해도 던지지 않는다.
 *
 * nginx 나 Cloudflare 가 거부하면 HTML 이 온다(413·429·403). 그때 JSON 파싱 예외로
 * 흐름이 끊기면 사용자는 아무 안내도 못 받는다. (SPEC §10.1)
 */
async function readBody(response: Response): Promise<ApiError | null> {
  try {
    return (await response.json()) as ApiError
  } catch {
    return null
  }
}

function retryAfterOf(response: Response): number | undefined {
  const raw = response.headers.get('Retry-After')
  if (!raw) return undefined
  const seconds = Number(raw)
  return Number.isFinite(seconds) ? seconds : undefined
}

interface RequestOptions {
  method?: string
  body?: unknown
  admin?: boolean
  signal?: AbortSignal
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, admin = false, signal } = options
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (admin) headers['X-Admin-Token'] = readAdminToken()

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  })

  if (!response.ok) {
    throw new ApiFailure(response.status, await readBody(response), retryAfterOf(response))
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

/** multipart 는 Content-Type 을 브라우저가 boundary 와 함께 정해야 하므로 따로 둔다. */
export async function postFile(
  path: string,
  file: File,
  signal: AbortSignal,
): Promise<Response> {
  const form = new FormData()
  form.append('file', file)
  return fetch(path, { method: 'POST', body: form, signal })
}

export { ApiFailure }

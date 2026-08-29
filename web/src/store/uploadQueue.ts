import { create } from 'zustand'
import { postFile } from '../api/client'
import { messageFor, rejectionDetail } from '../messages'
import type { ApiError, UploadResponse } from '../api/types'

/** SPEC §11.2 — 큐 길이 상한. 화면이 감당할 수 있는 범위로 자른다. */
export const QUEUE_LIMIT = 20

/** SPEC §11.3 — 선제 pacing. 앱 한도(60r/m)에 맞춰 429 를 애초에 안 받게 한다. */
const MIN_INTERVAL_MS = 1_000

/** SPEC §11.3 — 멈춘 것과 느린 것을 구분한다. 진행률 바 대신 이것을 쓴다. */
const TIMEOUT_MS = 60_000

const MAX_ATTEMPTS = 3

export type ItemStatus =
  | 'QUEUED'
  | 'UPLOADING'
  | 'RETRYING'
  | 'DONE'
  | 'REJECTED'
  | 'FAILED'
  | 'CANCELLED'

export interface QueueItem {
  id: string
  file: File
  status: ItemStatus
  /** 서버가 준 코드. 화면 문구는 이것으로 정한다. */
  code: string | null
  message: string
  /** "무엇이 / 왜" 의 뒷부분. 차단 사유를 구체적으로 보여준다. */
  reason: string | null
  /** 화면이 미리 짚어준 경고. 서버 판정을 대신하지 않는다. */
  hint: string | null
  attempts: number
  waitSeconds: number
  fileId: string | null
}

interface QueueState {
  items: QueueItem[]
  running: boolean
  enqueue: (files: File[], hintOf: (file: File) => string | null) => number
  remove: (id: string) => void
  cancel: (id: string) => void
  clearFinished: () => void
  run: () => Promise<void>
}

/** AbortController 는 직렬화되지 않으므로 상태 밖에 둔다. */
const controllers = new Map<string, AbortController>()

const TERMINAL: ItemStatus[] = ['DONE', 'REJECTED', 'FAILED', 'CANCELLED']

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** 지수 백오프 + jitter. 여러 파일이 동시에 같은 순간으로 몰리지 않게 한다. */
function backoffMs(attempt: number): number {
  const base = 1_000 * 2 ** (attempt - 1)
  return Math.round(base * (0.8 + Math.random() * 0.4))
}

/**
 * ★ 재시도 분류가 이 래퍼의 핵심이다. (SPEC §11.3)
 *
 * 정책 거부를 재시도하면 차단될 파일로 서버를 계속 때리고, 사용자에게는
 * "왜 아직도 안 되지" 만 보인다. 거부는 오류가 아니라 결과다.
 */
function isRetryable(status: number, code: string | null): boolean {
  // 첫 요청이 실제로 성공했을 수 있다. 재시도하면 같은 파일이 새 UUID 로 한 번 더 올라간다.
  if (code === 'UPLOAD_OUTCOME_UNKNOWN') return false
  if (status === 429) return true
  if (status >= 500) return true
  return false
}

export const useUploadQueue = create<QueueState>((set, get) => ({
  items: [],
  running: false,

  enqueue: (files, hintOf) => {
    const current = get().items
    const room = QUEUE_LIMIT - current.filter((item) => !TERMINAL.includes(item.status)).length
    const accepted = files.slice(0, Math.max(room, 0))

    set({
      items: [
        ...current,
        ...accepted.map((file) => ({
          id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          file,
          status: 'QUEUED' as ItemStatus,
          code: null,
          message: '대기 중',
          reason: null,
          hint: hintOf(file),
          attempts: 0,
          waitSeconds: 0,
          fileId: null,
        })),
      ],
    })
    void get().run()
    return files.length - accepted.length
  },

  remove: (id) => {
    const item = get().items.find((candidate) => candidate.id === id)
    if (item && item.status === 'UPLOADING') return
    set({ items: get().items.filter((candidate) => candidate.id !== id) })
  },

  cancel: (id) => {
    controllers.get(id)?.abort()
  },

  clearFinished: () => {
    set({ items: get().items.filter((item) => !TERMINAL.includes(item.status)) })
  },

  /**
   * 순차 실행기. 동시성 1 인 이유가 셋이다(SPEC §11.2) —
   * 진행 표시가 정확해지고, 브라우저 연결 한도에 안 걸리고,
   * 감사가 fail-closed 라 동시 요청이 몰리면 503 이 연쇄된다.
   */
  run: async () => {
    if (get().running) return
    set({ running: true })

    const patch = (id: string, changes: Partial<QueueItem>) =>
      set({
        items: get().items.map((item) => (item.id === id ? { ...item, ...changes } : item)),
      })

    try {
      let lastRequestAt = 0

      for (;;) {
        const next = get().items.find((item) => item.status === 'QUEUED')
        if (!next) break

        let attempt = 0
        for (;;) {
          attempt += 1

          const since = Date.now() - lastRequestAt
          if (since < MIN_INTERVAL_MS) await sleep(MIN_INTERVAL_MS - since)

          patch(next.id, { status: 'UPLOADING', message: '올리는 중', attempts: attempt })

          const controller = new AbortController()
          controllers.set(next.id, controller)
          // 취소와 타임아웃은 둘 다 AbortError 로 오지만 처리가 정반대다 —
          // 취소는 사용자의 뜻이므로 확정 종료, 타임아웃은 재시도 대상이다.
          let timedOut = false
          const timeout = setTimeout(() => {
            timedOut = true
            controller.abort()
          }, TIMEOUT_MS)

          try {
            lastRequestAt = Date.now()
            const response = await postFile('/api/files', next.file, controller.signal)

            if (response.ok) {
              const body = (await response.json()) as UploadResponse
              patch(next.id, { status: 'DONE', message: '완료', fileId: body.fileId })
              break
            }

            const body = await response.json().catch(() => null) as ApiError | null
            const code = body?.code ?? null

            if (!isRetryable(response.status, code)) {
              patch(next.id, {
                status: 'REJECTED',
                code,
                message: messageFor(code, response.status),
                reason: rejectionDetail(code, body?.detail),
              })
              break
            }

            if (attempt >= MAX_ATTEMPTS) {
              patch(next.id, { status: 'FAILED', code, message: messageFor(code, response.status) })
              break
            }

            const retryAfter = Number(response.headers.get('Retry-After'))
            const waitMs = Number.isFinite(retryAfter) && retryAfter > 0
              ? retryAfter * 1_000
              : backoffMs(attempt)
            patch(next.id, {
              status: 'RETRYING',
              code,
              message: `${messageFor(code, response.status)} (${Math.ceil(waitMs / 1000)}초 후 재시도)`,
              waitSeconds: Math.ceil(waitMs / 1000),
            })
            await sleep(waitMs)
          } catch (error) {
            const aborted = error instanceof DOMException && error.name === 'AbortError'

            if (aborted && !timedOut) {
              patch(next.id, { status: 'CANCELLED', message: '취소됨' })
              break
            }

            const failure = timedOut
              ? '응답이 없어 중단했어요.'
              : '네트워크에 연결하지 못했어요.'

            if (attempt >= MAX_ATTEMPTS) {
              patch(next.id, { status: 'FAILED', message: failure })
              break
            }
            const waitMs = backoffMs(attempt)
            patch(next.id, {
              status: 'RETRYING',
              message: `${failure} (${Math.ceil(waitMs / 1000)}초 후 재시도)`,
              waitSeconds: Math.ceil(waitMs / 1000),
            })
            await sleep(waitMs)
          } finally {
            clearTimeout(timeout)
            controllers.delete(next.id)
          }
        }
      }
    } finally {
      set({ running: false })
    }
  },
}))

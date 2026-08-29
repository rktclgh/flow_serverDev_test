import { create } from 'zustand'
import { postFile } from '../api/client'
import { messageFor, rejectionDetail, toneFor } from '../messages'
import { pushToast, type ToastInput } from '../toast'
import type { ApiError, UploadResponse } from '../api/types'

/** SPEC §11.2 — 큐 길이 상한. 화면이 감당할 수 있는 범위로 자른다. */
export const QUEUE_LIMIT = 20

/** SPEC §11.3 — 선제 pacing. 앱 한도(60r/m)에 맞춰 429 를 애초에 안 받게 한다. */
const MIN_INTERVAL_MS = 1_000

/** SPEC §11.3 — 멈춘 것과 느린 것을 구분한다. 진행률 바 대신 이것을 쓴다. */
const TIMEOUT_MS = 60_000

const MAX_ATTEMPTS = 3

/**
 * ★ 큐에는 **아직 처리 중인 것만** 남는다.
 *
 * 예전에는 `DONE / REJECTED / FAILED / CANCELLED` 도 상태로 두고 목록에 남겼다. 그래서
 * 파일 20개를 올리면 차단된 것들이 계속 쌓여 화면이 실패 로그가 됐다. 끝난 항목은
 * 토스트로 한 번 알리고 목록에서 뺀다 — 성공한 파일의 자리는 "업로드된 파일" 목록이고,
 * 거부된 파일은 애초에 남길 자리가 없다.
 */
export type ItemStatus = 'QUEUED' | 'UPLOADING' | 'RETRYING'

export interface QueueItem {
  id: string
  file: File
  status: ItemStatus
  message: string
  /** 화면이 미리 짚어준 경고. 서버 판정을 대신하지 않는다. */
  hint: string | null
  attempts: number
  waitSeconds: number
}

interface QueueState {
  items: QueueItem[]
  running: boolean
  /**
   * 이번 묶음의 진척. 끝난 항목이 목록에서 사라져도 진척은 보여야 한다.
   *
   * ★ 두 값의 뜻을 못 박아 둔다. 안 그러면 끝나도 `4/5` 에 멈춰 사용자가 뭔가 안 끝났다고 읽는다.
   *
   * - `total`    = **시도 대상으로 받아들인 것.** 시도하기 전에 큐에서 빼면 애초에 없던 일이므로
   *                `remove` 가 이 값도 함께 줄인다.
   * - `processed` = **결과를 알린 것.** 성공·거부·실패·취소 모두 결과이고 토스트로 한 번 알렸다.
   *
   * 취소도 `processed` 로 센다. 사용자가 의도한 것이라 실패는 아니지만(토스트도 info 다),
   * 요청은 이미 나갔고 결과를 알렸다 — `total` 이 이미 시도 대상으로 세어 둔 항목이므로
   * 여기서 빼면 도리어 짝이 안 맞는다. 대기 중 제거는 요청이 나가기 전이라 경우가 다르다.
   *
   * 불변식: 묶음이 끝나면 `processed === total`, 그리고 `processed` 는 띄운 결과 토스트 수와 같다.
   */
  processed: number
  total: number
  /**
   * 업로드 성공 횟수. "업로드된 파일" 목록이 이 값을 보고 다시 조회한다.
   *
   * 응답을 목록에 직접 끼워 넣지 않는 이유는 §11.1 의 "낙관적 반영 금지" 다 —
   * 화면이 들고 있는 응답이 아니라 서버가 실제로 갖고 있는 목록을 보여준다.
   */
  succeeded: number
  enqueue: (files: File[], hintOf: (file: File) => string | null) => void
  remove: (id: string) => void
  cancel: (id: string) => void
  run: () => Promise<void>
}

/** AbortController 는 직렬화되지 않으므로 상태 밖에 둔다. */
const controllers = new Map<string, AbortController>()

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
  processed: 0,
  total: 0,
  succeeded: 0,

  enqueue: (files, hintOf) => {
    const current = get().items
    const room = QUEUE_LIMIT - current.length
    const accepted = files.slice(0, Math.max(room, 0))
    const dropped = files.length - accepted.length

    // 앞 묶음이 다 끝난 뒤 새로 올리는 것이면 진척 카운터를 처음부터 센다.
    const fresh = current.length === 0 && !get().running

    set({
      processed: fresh ? 0 : get().processed,
      total: (fresh ? 0 : get().total) + accepted.length,
      items: [
        ...current,
        ...accepted.map((file) => ({
          id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          file,
          status: 'QUEUED' as ItemStatus,
          message: '대기 중',
          hint: hintOf(file),
          attempts: 0,
          waitSeconds: 0,
        })),
      ],
    })

    if (dropped > 0) {
      pushToast({
        tone: 'warning',
        title: `${dropped}개는 담지 못했어요`,
        body: `큐는 한 번에 ${QUEUE_LIMIT}개까지예요. 처리된 뒤에 다시 올려 주세요.`,
      })
    }
    void get().run()
  },

  /**
   * 대기 중인 항목을 큐에서 뺀다.
   *
   * `QUEUED` 만 뺄 수 있다. `UPLOADING` 과 `RETRYING` 은 실행기가 들고 있어서, 목록에서만
   * 지우면 실행기가 그대로 올린 뒤 `processed` 를 올려 `processed > total` 이 된다.
   * (진행 중인 것은 `cancel` 이, 재시도 대기는 그 다음 시도가 결론을 낸다.)
   */
  remove: (id) => {
    const item = get().items.find((candidate) => candidate.id === id)
    if (!item || item.status !== 'QUEUED') return
    set({
      items: get().items.filter((candidate) => candidate.id !== id),
      // 시도조차 하지 않은 것은 "받아들인 것" 에서도 뺀다. 안 그러면 끝나도 4/5 로 멈춘다.
      total: Math.max(get().total - 1, get().processed),
    })
  },

  cancel: (id) => {
    controllers.get(id)?.abort()
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

    /** 끝난 항목의 종착지. 사유를 토스트로 한 번 알리고 목록에서 뺀다. */
    const finish = (id: string, toast: ToastInput) => {
      pushToast(toast)
      set({
        items: get().items.filter((item) => item.id !== id),
        processed: get().processed + 1,
      })
    }

    try {
      let lastRequestAt = 0

      for (;;) {
        const next = get().items.find((item) => item.status === 'QUEUED')
        if (!next) break

        const filename = next.file.name
        let attempt = 0
        for (;;) {
          attempt += 1

          const since = Date.now() - lastRequestAt
          if (since < MIN_INTERVAL_MS) await sleep(MIN_INTERVAL_MS - since)

          // pacing 대기(최대 1초)는 사용자가 × 를 누르기 충분한 시간이다. 그 사이에 빠진
          // 항목을 그대로 올리면 이미 total 에서 빠진 것을 processed 로 세게 된다.
          if (!get().items.some((item) => item.id === next.id)) break

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
              set({ succeeded: get().succeeded + 1 })
              finish(next.id, {
                tone: 'success',
                title: body.originalFilename || filename,
                body: '업로드했어요.',
              })
              break
            }

            const body = (await response.json().catch(() => null)) as ApiError | null
            const code = body?.code ?? null

            if (!isRetryable(response.status, code)) {
              // 정책 거부는 다시 보내도 답이 같다. 여기서 확정하고 사유만 남긴다.
              finish(next.id, {
                tone: toneFor(code),
                title: filename,
                body: messageFor(code, response.status),
                detail: rejectionDetail(code, body?.detail),
              })
              break
            }

            if (attempt >= MAX_ATTEMPTS) {
              finish(next.id, {
                tone: 'error',
                title: filename,
                body: `${messageFor(code, response.status)} (${MAX_ATTEMPTS}번 시도했어요)`,
              })
              break
            }

            const retryAfter = Number(response.headers.get('Retry-After'))
            const waitMs =
              Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1_000 : backoffMs(attempt)
            // 재시도 대기는 아직 처리 중이다. 목록에 남겨 두고 다음 시도를 기다린다.
            patch(next.id, {
              status: 'RETRYING',
              message: `${messageFor(code, response.status)} (${Math.ceil(waitMs / 1000)}초 후 재시도)`,
              waitSeconds: Math.ceil(waitMs / 1000),
            })
            await sleep(waitMs)
          } catch (error) {
            const aborted = error instanceof DOMException && error.name === 'AbortError'

            if (aborted && !timedOut) {
              finish(next.id, { tone: 'info', title: filename, body: '업로드를 취소했어요.' })
              break
            }

            const failure = timedOut ? '응답이 없어 중단했어요.' : '네트워크에 연결하지 못했어요.'

            if (attempt >= MAX_ATTEMPTS) {
              finish(next.id, {
                tone: 'error',
                title: filename,
                body: `${failure} (${MAX_ATTEMPTS}번 시도했어요)`,
              })
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

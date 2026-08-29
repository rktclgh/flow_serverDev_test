import { useEffect, useState } from 'react'
import { subscribeToasts, type Toast, type ToastTone } from '../toast'

/**
 * 동시에 보이는 개수 상한.
 *
 * 파일 20개가 전부 차단되면 토스트도 20개가 된다. 그러면 "실패 로그" 가 목록에서 토스트로
 * 자리만 옮긴 꼴이 된다. 넘치면 **오래된 것부터** 밀어낸다 — 방금 올린 파일의 결과가
 * 가장 궁금하기 때문이다.
 */
const MAX_VISIBLE = 4

/** 사유는 읽을 시간이 필요하다. 성공은 짧아도 되지만 거부는 문장을 읽어야 한다. */
const LIFETIME_MS: Record<ToastTone, number> = {
  success: 3_500,
  info: 4_000,
  warning: 6_000,
  error: 7_000,
}

interface LiveToast extends Toast {
  /** 표시 시점에 못 박는다. 뒤에 온 토스트 때문에 앞의 수명이 늘어나면 안 된다. */
  expiresAt: number
}

/**
 * 토스트 표시부. (SPEC §19)
 *
 * 보관과 만료는 여기(useState)가 하고, 발행만 `toast.ts` 의 버스를 거친다.
 */
export function ToastHost() {
  const [toasts, setToasts] = useState<LiveToast[]>([])

  useEffect(() => {
    return subscribeToasts((toast) => {
      const live: LiveToast = { ...toast, expiresAt: Date.now() + LIFETIME_MS[toast.tone] }
      setToasts((current) => [...current, live].slice(-MAX_VISIBLE))
    })
  }, [])

  useEffect(() => {
    if (toasts.length === 0) return
    const earliest = Math.min(...toasts.map((toast) => toast.expiresAt))
    const timer = setTimeout(
      () => setToasts((current) => current.filter((toast) => toast.expiresAt > Date.now())),
      Math.max(earliest - Date.now(), 0),
    )
    return () => clearTimeout(timer)
  }, [toasts])

  if (toasts.length === 0) return null

  return (
    // 거부 사유는 읽히기 위한 것이지 흐름을 끊기 위한 것이 아니다. assertive 를 쓰지 않는다.
    <div className="toast-host" role="status" aria-live="polite">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast ${toast.tone}`}>
          <div className="toast-text">
            <strong>{toast.title}</strong>
            <span>{toast.body}</span>
            {/* 과제 "명확한 사유" — 무엇이 / 왜 를 토스트에서도 잃지 않는다 */}
            {toast.detail && <span className="toast-detail">{toast.detail}</span>}
          </div>
          <button
            type="button"
            aria-label="알림 닫기"
            onClick={() => setToasts((current) => current.filter((item) => item.id !== toast.id))}
          >
            ×
          </button>
        </div>
      ))}
    </div>
  )
}

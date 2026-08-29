/**
 * 일시적 알림 버스. (SPEC §19 — "에러 메시지 매핑 (toast)")
 *
 * ★ 거부는 로그가 아니라 알림이다.
 *
 * 이전에는 거부·실패·취소가 업로드 큐 목록에 그대로 남았다. 파일 20개를 올리면 차단된
 * 것들이 쌓여 화면이 실패 로그가 됐다. 거부는 **결과**이고 결과는 한 번 알리면 된다.
 * 그래서 사유는 토스트로 띄우고 큐에는 **아직 처리 중인 것만** 남긴다.
 *
 * ★ 왜 zustand 를 하나 더 만들지 않는가.
 *
 * 토스트를 발행하는 쪽이 컴포넌트 밖(순차 실행기)이라 React state 만으로는 닿지 않는다.
 * 그렇다고 스토어를 늘릴 이유는 없다 — 큐가 zustand 인 것은 실행기·재시도·취소가 컴포넌트
 * 생명주기 밖에 살아야 하기 때문이지 "전역이라서" 가 아니다. 토스트는 발행만 밖에서 하고
 * 보관·만료는 화면(ToastHost)이 useState 로 한다. 그래서 20줄짜리 구독 버스면 충분하다.
 */

export type ToastTone = 'error' | 'warning' | 'info' | 'success'

export interface Toast {
  id: string
  tone: ToastTone
  /** 무엇이 — 파일명처럼 사용자가 자기 행동과 이어붙일 수 있는 값. */
  title: string
  /** 왜 — 사유. `messages.ts` 의 매핑 문구가 들어온다. */
  body: string
  /** 판정 근거. `.exe — 고정 차단 목록` 처럼 구체적인 값. */
  detail?: string | null
}

export type ToastInput = Omit<Toast, 'id'>

type Listener = (toast: Toast) => void

const listeners = new Set<Listener>()

let sequence = 0

/** 컴포넌트 안팎 어디서든 부를 수 있다. 구독자가 없으면 조용히 버려진다. */
export function pushToast(input: ToastInput): void {
  sequence += 1
  const toast: Toast = { ...input, id: `toast-${sequence}` }
  for (const listener of listeners) listener(toast)
}

export function subscribeToasts(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

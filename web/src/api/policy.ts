import { request } from './client'
import type { PolicyResponse } from './types'

export function fetchPolicy(): Promise<PolicyResponse> {
  return request<PolicyResponse>('/api/extensions')
}

export function toggleFixed(name: string, blocked: boolean) {
  return request<{ name: string; blocked: boolean }>(`/api/extensions/fixed/${name}`, {
    method: 'PATCH',
    body: { blocked },
    admin: true,
  })
}

/** 서버가 정규화된 이름을 돌려준다. 사용자가 입력한 것과 다를 수 있다(SPEC §20). */
export function addCustom(name: string) {
  return request<{ name: string }>('/api/extensions/custom', {
    method: 'POST',
    body: { name },
    admin: true,
  })
}

export function removeCustom(name: string) {
  return request<void>(`/api/extensions/custom/${encodeURIComponent(name)}`, {
    method: 'DELETE',
    admin: true,
  })
}

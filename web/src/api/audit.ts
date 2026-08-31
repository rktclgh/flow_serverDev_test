import { request } from './client'
import type { AuditResponse } from './types'

/**
 * 활동 로그.
 *
 * ★ 읽기지만 관리 토큰이 필요하다. 다른 사용자가 올린 파일명과 차단 이력이 담기므로
 * "상태를 바꾸지 않는다" 와 "보여줘도 된다" 를 같은 것으로 두지 않았다.
 */
export function fetchAudit(limit = 50): Promise<AuditResponse> {
  return request<AuditResponse>(`/api/audit?limit=${limit}`, { admin: true })
}

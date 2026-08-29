import { request } from './client'
import type { FileListResponse } from './types'

/** 최신순 최대 100개. 페이지네이션은 없다 — 검증용 목록이라 그 이상 쌓이지 않는다. */
export function fetchFiles(): Promise<FileListResponse> {
  return request<FileListResponse>('/api/files')
}

/**
 * 다운로드 주소.
 *
 * ★ `fetch` 로 받아 blob 을 만들지 않는다. 서버가 `Content-Disposition: attachment` 를
 * 붙여 내려주므로 브라우저가 이미 파일로 저장한다. blob 을 거치면 10MB 를 메모리에 통째로
 * 올리고, 파일명은 서버가 정한 RFC 5987 값 대신 우리가 다시 조립해야 한다.
 * 링크 하나로 되는 일에 코드를 얹을 이유가 없다.
 */
export function fileContentUrl(fileId: string): string {
  return `/api/files/${encodeURIComponent(fileId)}/content`
}

/** 삭제는 관리 토큰이 필요하다. 성공은 204 — 본문이 없다. */
export function deleteFile(fileId: string): Promise<void> {
  return request<void>(`/api/files/${encodeURIComponent(fileId)}`, {
    method: 'DELETE',
    admin: true,
  })
}

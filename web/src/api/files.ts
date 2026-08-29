import { request } from './client'
import type { BulkDeleteResponse, FileListResponse } from './types'

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

/**
 * 여러 건을 한 번에 삭제한다.
 *
 * ★ 단건 삭제를 개수만큼 호출하지 않는다. 그러면 중간에 하나가 실패했을 때 "어디까지
 * 지워졌는가" 를 프론트가 조립해야 하고, 요청 수만큼 속도 제한에 걸린다.
 * 서버가 건별 결과를 한 번에 답한다.
 *
 * 200 이지만 전부 지워졌다는 뜻은 아니다 — notFound 를 반드시 확인해야 한다.
 * 목록이 낡아 이미 없는 파일을 고르는 일은 정상적으로 일어난다.
 */
export function deleteFiles(fileIds: string[]): Promise<BulkDeleteResponse> {
  return request<BulkDeleteResponse>('/api/files', {
    method: 'DELETE',
    body: { fileIds },
    admin: true,
  })
}

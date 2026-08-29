import { useCallback, useEffect, useState } from 'react'
import { deleteFile, fetchFiles, fileContentUrl } from '../api/files'
import { ApiFailure, type UploadedFile } from '../api/types'
import { messageFor, toneFor } from '../messages'
import { useUploadQueue } from '../store/uploadQueue'
import { pushToast } from '../toast'

/**
 * 카드 폭에 맞춘 표시 길이. 넘치면 가운데를 접는다.
 *
 * 한글은 글자당 폭이 라틴 문자의 두 배쯤이라 같은 글자 수라도 줄 수가 다르다.
 * 20자로 잡으면 한글 이름이 두 줄에 들어맞고, 라틴 이름은 그보다 여유가 있다.
 * (CSS 가 두 줄로 한 번 더 잘라 카드 높이가 들쭉날쭉해지는 것을 막는다.)
 */
const NAME_DISPLAY_MAX = 20

/**
 * 파일명은 <b>가운데</b>를 접는다.
 *
 * 뒤를 자르면 확장자가 사라진다. 이 화면은 확장자 차단을 다루는 화면이고, 무엇이
 * 올라갔는지 판단하는 근거의 절반이 확장자다. 앞뒤를 남기고 가운데를 접는다.
 */
function truncateMiddle(name: string, max = NAME_DISPLAY_MAX): string {
  if (name.length <= max) return name
  const head = Math.ceil((max - 1) / 2)
  const tail = Math.floor((max - 1) / 2)
  return `${name.slice(0, head)}…${name.slice(name.length - tail)}`
}

/** 사람이 읽는 단위로. 바이트 숫자는 사용자에게 아무 의미가 없다. */
function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '-'
  if (bytes < 1024) return `${bytes}B`
  const kb = bytes / 1024
  if (kb < 1024) return `${Math.round(kb)}KB`
  const mb = kb / 1024
  return mb < 10 ? `${mb.toFixed(1)}MB` : `${Math.round(mb)}MB`
}

/** 서버가 준 문자열을 그대로 믿지 않는다. 파싱에 실패하면 원본을 보여준다. */
function formatTime(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return iso
  return at.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  if (dot < 0 || dot === name.length - 1) return 'FILE'
  return name.slice(dot + 1).toUpperCase().slice(0, 4)
}

/**
 * 문서 아이콘. 외부 에셋을 늘리지 않으려고 인라인 SVG 로 그린다.
 *
 * 종류 구분은 확장자 글자로 충분하다 — 확장자별 아이콘 세트를 들이면 매핑에 없는
 * 확장자마다 기본 아이콘으로 떨어지고, 그 매핑을 계속 관리해야 한다.
 */
function FileIcon({ label }: { label: string }) {
  return (
    <svg className="file-icon" viewBox="0 0 48 58" aria-hidden="true">
      <path className="sheet" d="M4 4a4 4 0 0 1 4-4h22l14 14v40a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4Z" />
      <path className="fold" d="M30 0l14 14H34a4 4 0 0 1-4-4Z" />
      <text className="ext" x="24" y="40" textAnchor="middle">
        {label}
      </text>
    </svg>
  )
}

interface Props {
  /** 삭제는 관리 토큰이 필요하다. 없으면 버튼을 막고 이유를 알린다(SPEC §7.0). */
  hasAdminToken: boolean
}

/**
 * 업로드된 파일. (과제 2-2 "정상 파일은 업로드 성공 처리")
 *
 * ★ 업로드 결과가 있어야 할 자리다. 큐는 처리 중인 것만 보여주고, 성공한 파일은 여기 남는다.
 *
 * ★ 낙관적 반영을 하지 않는다(SPEC §11.1). 업로드 응답을 목록에 끼워 넣지 않고 서버에
 * 다시 물어본다. 삭제도 204 를 받은 뒤에 다시 조회한다 — 화면이 "지워졌다" 고 말했는데
 * 서버에 남아 있으면, 이 화면에서 그 오해는 곧 잘못된 안심이다.
 */
export function FilePanel({ hasAdminToken }: Props) {
  const [files, setFiles] = useState<UploadedFile[]>([])
  const [loaded, setLoaded] = useState(false)
  const [deleting, setDeleting] = useState<string | null>(null)
  const succeeded = useUploadQueue((state) => state.succeeded)

  /**
   * 조회 실패는 조용히 빈 목록으로 둔다.
   *
   * 이 목록은 업로드의 <b>결과 확인</b>용 보조 화면이다. 서버가 아직 이 API 를 갖고 있지
   * 않거나 잠시 실패했다고 해서 에러 배너로 화면을 덮으면, 정작 중요한 정책·업로드 화면이
   * 망가진 것처럼 보인다. 원인은 콘솔에 남긴다.
   */
  const reload = useCallback(async () => {
    try {
      const response = await fetchFiles()
      setFiles(response.files ?? [])
    } catch (error) {
      console.warn('[ExtGuard] 업로드된 파일 목록을 불러오지 못했습니다.', error)
      setFiles([])
    } finally {
      setLoaded(true)
    }
  }, [])

  // 업로드가 성공할 때마다 서버에 다시 물어본다.
  useEffect(() => {
    void reload()
  }, [reload, succeeded])

  const onDelete = async (file: UploadedFile) => {
    // 되돌릴 수 없다. 카드 하나 차이로 다른 파일을 지우는 일이 없게 이름을 문장에 넣는다.
    if (!window.confirm(`'${file.originalFilename}' 을 삭제할까요?\n되돌릴 수 없어요.`)) return

    setDeleting(file.fileId)
    try {
      await deleteFile(file.fileId)
      pushToast({ tone: 'success', title: file.originalFilename, body: '삭제했어요.' })
    } catch (failure) {
      if (failure instanceof ApiFailure) {
        pushToast({
          tone: toneFor(failure.code),
          title: file.originalFilename,
          body: messageFor(failure.code, failure.status),
        })
      } else {
        pushToast({ tone: 'error', title: file.originalFilename, body: '요청을 보내지 못했어요.' })
      }
    } finally {
      setDeleting(null)
      // 성공이든 실패든 서버 상태로 맞춘다. 404 였다면 다른 탭에서 이미 지운 것이다.
      await reload()
    }
  }

  return (
    <section className="panel files">
      <h2>업로드된 파일</h2>
      <p className="muted">카드를 누르면 내려받아요. 삭제는 관리 토큰이 필요해요.</p>

      {!loaded && <p className="muted">불러오는 중…</p>}
      {loaded && files.length === 0 && <p className="muted">아직 올린 파일이 없어요.</p>}

      {files.length > 0 && (
        <ul className="file-grid">
          {files.map((file) => (
            <li key={file.fileId} className="file-card">
              {/*
                카드 본체가 곧 다운로드 링크다. 삭제 버튼은 이 링크 <b>밖</b>에 두고 위에 얹는다 —
                링크 안에 버튼을 넣으면 마크업이 어긋나고, 지우려던 클릭이 다운로드까지 발동한다.
              */}
              <a className="file-body" href={fileContentUrl(file.fileId)} title={file.originalFilename}>
                <FileIcon label={extensionOf(file.originalFilename)} />
                <span className="file-name">{truncateMiddle(file.originalFilename)}</span>
                <span className="file-size">{formatSize(file.size)}</span>
                <time className="file-time" dateTime={file.uploadedAt}>
                  {formatTime(file.uploadedAt)}
                </time>
              </a>
              <button
                type="button"
                className="file-delete"
                disabled={!hasAdminToken || deleting === file.fileId}
                title={hasAdminToken ? '삭제' : '관리 토큰을 저장해야 삭제할 수 있어요'}
                aria-label={
                  hasAdminToken
                    ? `${file.originalFilename} 삭제`
                    : `${file.originalFilename} 삭제 — 관리 토큰이 필요해요`
                }
                onClick={() => void onDelete(file)}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

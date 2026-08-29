import { useRef, useState } from 'react'
import { QUEUE_LIMIT, useUploadQueue, type ItemStatus } from '../store/uploadQueue'
import type { PolicyResponse } from '../api/types'

/** 서버 상한과 같은 값. 어디까지나 안내용이고 실제 거부는 서버가 한다. */
const SIZE_LIMIT = 10 * 1024 * 1024

const LABELS: Record<ItemStatus, string> = {
  QUEUED: '대기',
  UPLOADING: '올리는 중',
  RETRYING: '재시도 대기',
  DONE: '완료',
  REJECTED: '차단됨',
  FAILED: '실패',
  CANCELLED: '취소됨',
}

interface Props {
  policy: PolicyResponse | null
}

/**
 * 업로드 큐. (과제 2-2, SPEC §11.2)
 *
 * 여러 파일을 한 요청에 담지 않는다. 큐는 화면이 들고 파일 하나당 요청 하나를 순차로 보낸다 —
 * 부분 성공이 자연스럽고, 파일마다 다른 거부 사유를 그대로 보여줄 수 있다.
 */
export function UploadPanel({ policy }: Props) {
  const { items, enqueue, remove, cancel, clearFinished } = useUploadQueue()
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  /**
   * 올리기 전에 짚어주는 경고. <b>서버 판정을 대신하지 않는다</b>(SPEC §11.2).
   * 확장자를 바꾼 실행 파일은 여기서 못 걸러내고 서버의 내용 검사가 잡는다.
   */
  const hintOf = (file: File): string | null => {
    if (file.size === 0) return '빈 파일이에요'
    if (file.size > SIZE_LIMIT) return '10MB를 넘어요'
    const dot = file.name.lastIndexOf('.')
    if (dot < 0 || dot === file.name.length - 1) return '확장자가 없어요'
    const extension = file.name.slice(dot + 1).toLowerCase()
    const blocked =
      policy?.fixed.some((item) => item.name === extension && item.blocked) ||
      policy?.custom.some((item) => item.name === extension)
    return blocked ? `.${extension}는 차단 목록에 있어요` : null
  }

  const accept = (files: FileList | null) => {
    if (!files || files.length === 0) return
    const dropped = enqueue(Array.from(files), hintOf)
    setNotice(dropped > 0 ? `큐가 가득 차 ${dropped}개는 담지 못했어요. (최대 ${QUEUE_LIMIT}개)` : null)
  }

  const finished = items.filter((item) =>
    ['DONE', 'REJECTED', 'FAILED', 'CANCELLED'].includes(item.status),
  ).length

  return (
    <section className="panel">
      <h2>파일 업로드</h2>

      <div
        className={dragging ? 'dropzone dragging' : 'dropzone'}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault()
          setDragging(false)
          accept(event.dataTransfer.files)
        }}
      >
        <p>파일을 끌어다 놓거나</p>
        <button type="button" onClick={() => inputRef.current?.click()}>
          파일 선택
        </button>
        <input
          ref={inputRef}
          type="file"
          multiple
          hidden
          onChange={(event) => {
            accept(event.target.files)
            event.target.value = ''
          }}
        />
        <p className="muted">한 번에 최대 {QUEUE_LIMIT}개, 파일당 10MB까지. 하나씩 차례로 올라가요.</p>
      </div>

      {notice && <p className="warn" role="status">{notice}</p>}

      {items.length > 0 && (
        <>
          <div className="queue-head">
            <span>
              {finished}/{items.length} 완료
            </span>
            <button type="button" onClick={clearFinished} disabled={finished === 0}>
              끝난 항목 지우기
            </button>
          </div>

          <ul className="queue">
            {items.map((item) => (
              <li key={item.id} className={`queue-item ${item.status.toLowerCase()}`}>
                <div className="queue-main">
                  <span className="filename" title={item.file.name}>
                    {item.file.name}
                  </span>
                  <span className="status">{LABELS[item.status]}</span>
                </div>
                <div className="queue-sub">
                  <span className="message">{item.message}</span>
                  {/* 과제 2-2 "명확한 사유" — 무엇이 / 왜 를 함께 보여준다 */}
                  {item.reason && <span className="reason">{item.reason}</span>}
                  {item.status === 'QUEUED' && item.hint && (
                    <span className="hint">{item.hint}</span>
                  )}
                </div>
                {item.status === 'QUEUED' && (
                  <button type="button" onClick={() => remove(item.id)} aria-label="큐에서 제거">
                    ×
                  </button>
                )}
                {item.status === 'UPLOADING' && (
                  <button type="button" onClick={() => cancel(item.id)}>
                    취소
                  </button>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

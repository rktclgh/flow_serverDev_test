import { useCallback, useEffect, useState } from 'react'
import { fetchAudit } from '../api/audit'
import type { AuditEntry } from '../api/types'
import { useUploadQueue } from '../store/uploadQueue'

/** 판정·변경 종류를 한국어 한 낱말로. 로그는 훑는 것이라 코드값을 그대로 두면 읽히지 않는다. */
const LABEL: Record<string, string> = {
  ALLOWED: '허용',
  BLOCKED: '차단',
  ERROR: '오류',
  PENDING: '진행 중',
  FIXED_BLOCK: '차단 켬',
  FIXED_UNBLOCK: '차단 끔',
  CUSTOM_ADD: '커스텀 추가',
  CUSTOM_DELETE: '커스텀 삭제',
}

/** 색은 세 갈래로만 나눈다. 종류마다 색을 주면 훑을 때 무엇이 문제인지 눈에 안 들어온다. */
function toneOf(entry: AuditEntry): string {
  if (entry.action === 'BLOCKED' || entry.action === 'ERROR') return 'bad'
  if (entry.kind === 'POLICY') return 'policy'
  return 'ok'
}

function formatTime(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return iso
  return at.toLocaleString('ko-KR', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}

interface Props {
  /** 로그 조회에도 관리 토큰이 필요하다 — 읽기 공개 규칙의 유일한 예외다. */
  hasAdminToken: boolean
  /** 정책이 바뀔 때마다 증가한다. 이 값이 변하면 로그를 다시 읽는다. */
  policyRevision: number
}

/**
 * 활동 로그.
 *
 * ★ 정책 변경과 업로드 판정을 <b>한 줄기로</b> 보여준다. 둘을 나눠 두면 "차단을 켠 뒤에 그
 * 파일이 막혔다" 는 인과가 두 목록에 흩어져, 정작 확인하고 싶은 것이 보이지 않는다.
 *
 * ★ 서버가 두 기록을 합쳐 내려주므로 화면은 종류별로 분기하지 않는다. 화면이 합치면
 * 표시 규칙이 둘로 갈라진다.
 *
 * ★ 요청자 주소를 함께 보여준다. 지금은 관리 토큰이 하나라 "누가 바꿨는가" 를 계정으로
 * 구분할 수 없고, 주소가 그 자리를 대신한다.
 */
export function AuditPanel({ hasAdminToken, policyRevision }: Props) {
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [loaded, setLoaded] = useState(false)
  const succeeded = useUploadQueue((state) => state.succeeded)

  const reload = useCallback(async () => {
    if (!hasAdminToken) {
      setEntries([])
      setLoaded(false)
      return
    }
    try {
      const response = await fetchAudit()
      setEntries(response.entries ?? [])
    } catch (error) {
      // 로그는 보조 화면이다. 실패했다고 에러 배너로 덮으면 정작 중요한 정책·업로드 화면이
      // 망가진 것처럼 보인다. 원인은 콘솔에 남긴다.
      console.warn('[ExtGuard] 활동 로그를 불러오지 못했습니다.', error)
      setEntries([])
    } finally {
      setLoaded(true)
    }
  }, [hasAdminToken])

  // 업로드 성공·정책 변경·토큰 저장 때마다 다시 물어본다.
  useEffect(() => {
    void reload()
  }, [reload, succeeded, policyRevision])

  return (
    <section className="panel audit">
      <div className="audit-head">
        <h2>활동 로그</h2>
        <button type="button" onClick={() => void reload()} disabled={!hasAdminToken}>
          새로고침
        </button>
      </div>
      <p className="muted">정책 변경과 업로드 판정을 시간순으로 보여줘요. 관리 토큰이 필요해요.</p>

      {!hasAdminToken && <p className="muted">관리 토큰을 저장하면 볼 수 있어요.</p>}
      {hasAdminToken && loaded && entries.length === 0 && <p className="muted">아직 기록이 없어요.</p>}

      {entries.length > 0 && (
        <ul className="audit-list">
          {entries.map((entry, index) => (
            <li key={`${entry.at}-${index}`} className={`audit-row ${toneOf(entry)}`}>
              <time dateTime={entry.at}>{formatTime(entry.at)}</time>
              <span className="audit-kind">{entry.kind === 'POLICY' ? '정책' : '업로드'}</span>
              <span className="audit-action">{LABEL[entry.action] ?? entry.action}</span>
              <span className="audit-target" title={entry.target}>{entry.target}</span>
              <span className="audit-detail">{entry.detail ?? ''}</span>
              <span className="audit-ip" title="요청자 주소">{entry.clientIp ?? '—'}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

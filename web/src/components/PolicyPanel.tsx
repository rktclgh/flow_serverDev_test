import { useCallback, useEffect, useState } from 'react'
import { addCustom, fetchPolicy, removeCustom, toggleFixed } from '../api/policy'
import { ApiFailure, type PolicyResponse } from '../api/types'
import { messageFor } from '../messages'

/** 서버가 정한 상한. 화면은 안내만 하고 실제 방어는 DB 슬롯이 한다. */
const NAME_MAX = 20

interface Props {
  onPolicyChange: (policy: PolicyResponse) => void
}

/**
 * 확장자 차단 정책. (과제 2-1, SPEC §11.1)
 *
 * ★ 낙관적 반영을 하지 않는다. 서버 응답을 받은 뒤에만 목록을 갱신한다 — 실패했는데
 * 화면만 바뀌면 사용자는 차단됐다고 믿고 실제로는 안 막힌다. 이 화면에서 그 오해는
 * 곧 보안 사고다.
 */
export function PolicyPanel({ onPolicyChange }: Props) {
  const [policy, setPolicy] = useState<PolicyResponse | null>(null)
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    try {
      const next = await fetchPolicy()
      setPolicy(next)
      onPolicyChange(next)
    } catch {
      setError('정책을 불러오지 못했어요.')
    }
  }, [onPolicyChange])

  useEffect(() => {
    void reload()
  }, [reload])

  /** 실패하면 목록을 다시 읽는다. 다른 탭에서 바뀌었을 수 있다(SPEC §11.1). */
  const runAdmin = async (action: () => Promise<unknown>) => {
    setBusy(true)
    setError(null)
    try {
      await action()
      await reload()
    } catch (failure) {
      if (failure instanceof ApiFailure) {
        setError(messageFor(failure.code, failure.status))
        await reload()
      } else {
        setError('요청을 보내지 못했어요.')
      }
    } finally {
      setBusy(false)
    }
  }

  if (!policy) {
    return <section className="panel"><h2>확장자 차단</h2><p className="muted">불러오는 중…</p></section>
  }

  const full = policy.customCount >= policy.customLimit
  const normalized = input.trim().replace(/^\.+/, '').toLowerCase()
  const duplicate =
    policy.custom.some((item) => item.name === normalized) ||
    policy.fixed.some((item) => item.name === normalized)

  return (
    <section className="panel">
      <h2>확장자 차단</h2>

      <h3>고정 확장자</h3>
      <p className="muted">자주 쓰이는 위험 확장자예요. 체크하면 업로드가 막혀요.</p>
      <div className="fixed-grid">
        {policy.fixed.map((item) => (
          <label key={item.name} className="checkbox">
            <input
              type="checkbox"
              checked={item.blocked}
              disabled={busy}
              onChange={(event) =>
                void runAdmin(() => toggleFixed(item.name, event.target.checked))
              }
            />
            <span>{item.name}</span>
          </label>
        ))}
      </div>

      <h3>
        커스텀 확장자{' '}
        <span className={full ? 'count full' : 'count'}>
          {policy.customCount}/{policy.customLimit}
        </span>
      </h3>

      <form
        className="add-row"
        onSubmit={(event) => {
          event.preventDefault()
          if (!input.trim() || busy || full || duplicate) return
          void runAdmin(async () => {
            await addCustom(input.trim())
            setInput('')
          })
        }}
      >
        <input
          value={input}
          maxLength={NAME_MAX}
          placeholder="확장자 입력 (최대 20자)"
          aria-label="추가할 확장자"
          disabled={busy || full}
          onChange={(event) => setInput(event.target.value)}
        />
        <button type="submit" disabled={busy || full || !input.trim() || duplicate}>
          추가
        </button>
      </form>

      {/*
        화면에서 먼저 알려주되 서버 판정을 대신하지 않는다. 서버는 NFKC 정규화까지 하므로
        여기서 같다고 본 것과 다를 수 있고, 최종 판단은 항상 서버가 한다.
      */}
      {duplicate && normalized && <p className="warn">이미 목록에 있어요.</p>}
      {full && <p className="warn">상한에 도달했어요. 지운 뒤에 추가할 수 있어요.</p>}
      {error && <p className="error" role="alert">{error}</p>}

      <ul className="chips">
        {policy.custom.map((item) => (
          <li key={item.name} className="chip">
            <span>{item.name}</span>
            <button
              type="button"
              aria-label={`${item.name} 삭제`}
              disabled={busy}
              onClick={() => void runAdmin(() => removeCustom(item.name))}
            >
              ×
            </button>
          </li>
        ))}
        {policy.custom.length === 0 && <li className="muted">아직 없어요.</li>}
      </ul>
    </section>
  )
}

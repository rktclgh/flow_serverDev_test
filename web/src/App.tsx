import { useCallback, useState } from 'react'
import { AdminToken } from './components/AdminToken'
import { AuditPanel } from './components/AuditPanel'
import { FilePanel } from './components/FilePanel'
import { PolicyPanel } from './components/PolicyPanel'
import { ToastHost } from './components/ToastHost'
import { UploadPanel } from './components/UploadPanel'
import { readAdminToken } from './api/client'
import type { PolicyResponse } from './api/types'
import './App.css'

export default function App() {
  const [policy, setPolicy] = useState<PolicyResponse | null>(null)
  // 정책이 바뀔 때마다 증가한다. 활동 로그가 이 값을 보고 다시 읽는다 —
  // 정책 화면과 로그가 서로를 모르게 두면서도 "바뀌면 갱신" 을 만족시키는 가장 얇은 연결이다.
  const [policyRevision, setPolicyRevision] = useState(0)
  const onPolicyChange = useCallback((next: PolicyResponse) => {
    setPolicy(next)
    setPolicyRevision((n) => n + 1)
  }, [])

  /**
   * 토큰 자체는 sessionStorage 가 갖고 있지만 그 변화는 React 가 모른다.
   * 삭제 버튼의 활성 여부가 토큰 유무에 달려 있으므로 "있는가" 만 여기서 들고 있는다.
   * 값을 state 로 끌어올리지 않는 이유는 토큰을 컴포넌트 트리에 흘리지 않기 위해서다.
   */
  const [hasAdminToken, setHasAdminToken] = useState(() => readAdminToken().length > 0)

  return (
    <div className="app">
      <header>
        <h1>UploadGuard</h1>
        <p className="muted">차단할 확장자를 정하고, 그 정책으로 파일 업로드를 막습니다.</p>
        <AdminToken onSaved={(token) => setHasAdminToken(token.length > 0)} />
      </header>

      <main>
        <PolicyPanel onPolicyChange={onPolicyChange} />
        <UploadPanel policy={policy} />
        <FilePanel hasAdminToken={hasAdminToken} />
        <AuditPanel hasAdminToken={hasAdminToken} policyRevision={policyRevision} />
      </main>

      <ToastHost />
    </div>
  )
}

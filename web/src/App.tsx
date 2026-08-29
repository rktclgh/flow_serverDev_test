import { useCallback, useState } from 'react'
import { AdminToken } from './components/AdminToken'
import { PolicyPanel } from './components/PolicyPanel'
import { ToastHost } from './components/ToastHost'
import { UploadPanel } from './components/UploadPanel'
import type { PolicyResponse } from './api/types'
import './App.css'

export default function App() {
  const [policy, setPolicy] = useState<PolicyResponse | null>(null)
  const onPolicyChange = useCallback((next: PolicyResponse) => setPolicy(next), [])

  return (
    <div className="app">
      <header>
        <h1>ExtGuard</h1>
        <p className="muted">차단할 확장자를 정하고, 그 정책으로 파일 업로드를 막습니다.</p>
        <AdminToken />
      </header>

      <main>
        <PolicyPanel onPolicyChange={onPolicyChange} />
        <UploadPanel policy={policy} />
      </main>

      <ToastHost />
    </div>
  )
}

import { useState } from 'react'
import { readAdminToken, writeAdminToken } from '../api/client'

/**
 * 관리 토큰 입력. (SPEC §11.1)
 *
 * 정책 변경만 토큰을 요구한다. 조회와 업로드는 공개다 — 과제가 "누구나 접속 가능" 을
 * 요구하므로 화면 전체를 잠그면 그 요구를 어긴다.
 */
export function AdminToken() {
  const [token, setToken] = useState(readAdminToken)
  const [saved, setSaved] = useState(false)

  return (
    <div className="admin-token">
      <label htmlFor="admin-token">관리 토큰</label>
      <input
        id="admin-token"
        type="password"
        value={token}
        placeholder="정책을 바꾸려면 필요해요"
        onChange={(event) => {
          setToken(event.target.value)
          setSaved(false)
        }}
      />
      <button
        type="button"
        onClick={() => {
          writeAdminToken(token.trim())
          setSaved(true)
        }}
      >
        저장
      </button>
      <span className="hint">
        {saved ? '이 탭에만 저장했어요.' : '탭을 닫으면 사라져요.'}
      </span>
    </div>
  )
}

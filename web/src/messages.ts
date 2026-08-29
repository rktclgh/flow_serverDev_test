import type { ToastTone } from './toast'

/**
 * 서버 코드 → 사용자 문구. (SPEC §19)
 *
 * ★ 서버 message 를 그대로 띄우지 않는다. 서버 메시지는 개발자를 향한 것이고 내부 구조가
 * 드러날 수 있다. 화면 문구는 화면이 정한다 — 코드가 계약이고 문구는 표현이다.
 */
const MESSAGES: Record<string, string> = {
  EXT_INVALID_FORMAT: '확장자는 영문 소문자와 숫자만 쓸 수 있어요.',
  EXT_TOO_LONG: '확장자는 20자까지 입력할 수 있어요.',
  EXT_DUPLICATE: '이미 추가되어 있어요.',
  EXT_FIXED_CONFLICT: '고정 확장자예요. 위쪽 목록에서 체크해 주세요.',
  EXT_LIMIT_EXCEEDED: '커스텀 확장자는 200개까지 추가할 수 있어요.',
  EXT_NOT_FOUND: '이미 삭제된 항목이에요. 목록을 새로고침할게요.',
  EXT_FIXED_NOT_DELETABLE: '고정 확장자는 삭제할 수 없어요. 체크를 해제해 주세요.',
  EXT_SLOT_CONFLICT: '다른 작업과 겹쳤어요. 다시 시도해 주세요.',

  FILE_BLOCKED_EXTENSION: '차단 목록에 있는 확장자예요.',
  FILE_EXECUTABLE_CONTENT: '파일 내용이 실행 파일이에요. 확장자를 바꿔도 올릴 수 없어요.',
  FILE_EXTENSION_MISSING: '확장자가 없는 파일은 올릴 수 없어요.',
  FILE_NAME_INVALID: '파일 이름에 쓸 수 없는 문자가 있어요.',
  FILE_NAME_TOO_LONG: '파일 이름이 너무 길어요. (255자까지)',
  FILE_COUNT_EXCEEDED: '한 번에 한 개씩만 올릴 수 있어요.',
  FILE_EMPTY: '빈 파일은 올릴 수 없어요.',
  FILE_TOO_LARGE: '파일이 너무 커요. 10MB까지 올릴 수 있어요.',
  FILE_REQUIRED: '올릴 파일을 찾지 못했어요.',
  RATE_LIMITED: '요청이 많아 잠시 기다릴게요.',
  STORAGE_UNAVAILABLE: '저장소에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
  UPLOAD_OUTCOME_UNKNOWN: '저장됐는지 확인할 수 없어요. 목록에서 확인한 뒤 다시 올려 주세요.',

  ADMIN_TOKEN_REQUIRED: '관리 토큰을 입력해 주세요.',
  ADMIN_TOKEN_INVALID: '관리 토큰이 올바르지 않아요.',
  ADMIN_TOKEN_NOT_CONFIGURED: '서버에 관리 토큰이 설정되지 않았어요.',
  REQUEST_INVALID: '요청 형식이 올바르지 않아요.',
}

/**
 * 코드가 없는 실패도 안내한다.
 *
 * 프록시(nginx·Cloudflare)가 거부하면 JSON 이 아니라 HTML 이 오고 코드가 없다.
 * 그때 "알 수 없는 오류" 로 끝내면 사용자는 파일이 큰 것인지 서버가 죽은 것인지 모른다.
 */
export function messageFor(code: string | null, status?: number): string {
  if (code && MESSAGES[code]) return MESSAGES[code]
  // 매핑에 없는 코드가 와도 화면이 침묵하지 않는다. 사용자에겐 일반 문구, 콘솔엔 원본 코드.
  if (code) console.warn(`[ExtGuard] 매핑되지 않은 응답 코드: ${code} (HTTP ${status ?? '?'})`)
  if (status === 413) return '파일이 너무 커요. (서버 앞단에서 거부됐어요)'
  if (status === 429) return '요청이 많아요. 잠시 후 다시 시도해 주세요.'
  if (status === 403) return '요청이 차단됐어요. 주소나 파일 이름을 확인해 주세요.'
  if (status && status >= 500) return '서버에 문제가 있어요. 잠시 후 다시 시도해 주세요.'
  return '요청을 처리하지 못했어요.'
}

/**
 * 코드 → 토스트 유형. (SPEC §19 매핑표의 "유형" 열)
 *
 * 전부 빨간 error 로 띄우면 "빈 파일이에요" 와 "실행 파일이에요" 가 같은 무게로 보인다.
 * 사용자가 고칠 수 있는 실수(warning)와 정책이 막은 것(error), 서버가 알려주는 상황(info)은
 * 다르게 읽혀야 한다. 매핑에 없으면 error 로 둔다 — 모르는 실패를 조용한 색으로 흘리지 않는다.
 */
const TONES: Record<string, ToastTone> = {
  EXT_DUPLICATE: 'warning',
  EXT_LIMIT_EXCEEDED: 'warning',
  EXT_FIXED_CONFLICT: 'info',
  EXT_NOT_FOUND: 'info',
  EXT_FIXED_NOT_DELETABLE: 'info',

  FILE_COUNT_EXCEEDED: 'warning',
  FILE_EMPTY: 'warning',
  // 첫 요청이 성공했을 수 있다. 실패라고 단정하지 않는다(§21.6).
  UPLOAD_OUTCOME_UNKNOWN: 'warning',
  RATE_LIMITED: 'info',
}

export function toneFor(code: string | null): ToastTone {
  return (code && TONES[code]) || 'error'
}

/** 차단 사유를 "무엇이 / 왜" 로 만든다. (과제 2-2 "명확한 사유") */
export function rejectionDetail(code: string | null, detail?: Record<string, unknown>): string | null {
  if (code === 'FILE_BLOCKED_EXTENSION' && typeof detail?.blockedExtension === 'string') {
    const type = detail.policyType === 'FIXED' ? '고정' : '커스텀'
    return `.${detail.blockedExtension} — ${type} 차단 목록`
  }
  if (code === 'FILE_EXECUTABLE_CONTENT' && typeof detail?.signature === 'string') {
    const names: Record<string, string> = {
      WINDOWS_PE: 'Windows 실행 파일(MZ)',
      ELF: 'Linux 실행 파일(ELF)',
      SHEBANG: '스크립트(#!)',
    }
    return `내용이 ${names[detail.signature] ?? detail.signature} 형식`
  }
  return null
}

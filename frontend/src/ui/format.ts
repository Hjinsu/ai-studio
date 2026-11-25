import { ApiError } from '../api/client'

/** 사용자(브라우저) 시간대. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

/**
 * UTC 저장 시각을 지정 시간대(채널·bucket 시간대, 없으면 브라우저)로 표시한다. 시간대 이름을 함께 붙인다.
 */
export function formatInstant(iso: string | null | undefined, timeZone?: string): string {
  if (iso === null || iso === undefined) {
    return '—'
  }
  const zone = timeZone ?? browserTimeZone()
  const text = new Intl.DateTimeFormat('ko-KR', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(iso))
  return `${text} (${zone})`
}

/** 금액은 API 문자열을 변환 없이 표시한다(ADR-035). */
export function money(amount: string | null | undefined, currency = 'KRW'): string {
  return amount === null || amount === undefined ? '—' : `${amount} ${currency}`
}

const MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: '입력값을 확인하세요.',
  UNAUTHORIZED: '로그인이 필요합니다.',
  FORBIDDEN: '권한이 없거나 보안 토큰이 만료되었습니다. 다시 시도하세요.',
  RESOURCE_NOT_FOUND: '대상을 찾을 수 없습니다.',
  RESOURCE_CONFLICT: '다른 변경과 충돌했습니다. 새로고침 후 다시 시도하세요.',
  DUPLICATE_RESOURCE: '이미 존재합니다.',
  INVALID_STATE_TRANSITION: '현재 상태에서는 할 수 없는 작업입니다.',
  BUDGET_EXCEEDED: '예산 한도를 초과합니다.',
  POLICY_VIOLATION: '정책에 의해 거부되었습니다.',
  PREREQUISITE_NOT_MET: '선행 조건이 충족되지 않았습니다.',
  RATE_LIMIT_EXCEEDED: '시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요.',
}

export interface ErrorView {
  title: string
  message: string | null
  details: string[]
  traceId: string | null
}

export function describeError(error: unknown): ErrorView {
  if (error instanceof ApiError) {
    const body = error.body
    return {
      title: (body && MESSAGES[body.code]) ?? `요청이 실패했습니다 (HTTP ${error.status}).`,
      message: body?.message ?? null,
      details: body?.details.map((d) => (d.field ? `${d.field}: ${d.reason}` : d.reason)) ?? [],
      traceId: body?.traceId ?? null,
    }
  }
  return { title: '서버에 연결할 수 없습니다.', message: null, details: [], traceId: null }
}

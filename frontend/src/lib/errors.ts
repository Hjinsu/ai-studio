import { ApiError } from '../api/client'

const MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: '입력값을 확인하세요.',
  UNAUTHORIZED: '로그인이 필요합니다.',
  FORBIDDEN: '이 작업을 할 권한이 없거나 보안 토큰이 만료되었습니다. 새로고침 후 다시 시도하세요.',
  RESOURCE_NOT_FOUND: '대상을 찾을 수 없습니다. 삭제되었거나 주소가 잘못되었습니다.',
  RESOURCE_CONFLICT: '다른 변경과 충돌했습니다. 새로고침해 최신 내용을 확인한 뒤 다시 시도하세요.',
  DUPLICATE_RESOURCE: '같은 항목이 이미 있습니다.',
  INVALID_STATE_TRANSITION: '현재 상태에서는 이 작업을 할 수 없습니다.',
  BUDGET_EXCEEDED: '예산 한도를 넘습니다.',
  POLICY_VIOLATION: '정책에 의해 거부되었습니다.',
  PREREQUISITE_NOT_MET: '선행 조건이 충족되지 않았습니다.',
  RATE_LIMIT_EXCEEDED: '요청이 일시적으로 제한되었습니다.',
  INTERNAL_SERVER_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.',
}

export type ErrorKind = 'unauthorized' | 'forbidden' | 'notFound' | 'network' | 'other'

export interface ErrorView {
  kind: ErrorKind
  title: string
  message: string | null
  details: string[]
  traceId: string | null
}

/** 재시도 대기 시간 표시: 60초 미만은 초, 이상은 분(올림). */
export function formatRetryAfter(seconds: number): string {
  return seconds < 60 ? `${Math.max(1, seconds)}초` : `약 ${Math.ceil(seconds / 60)}분`
}

/**
 * 429는 로그인 실패 반복 차단과 동시 인증 요청 제한(RV-08)에 같은 코드·메시지로 쓰인다. 원인을 단정하지 않고
 * 두 경우를 함께 안내하며 Retry-After가 있으면 대기 시간을 알려준다.
 */
function rateLimitDetail(error: ApiError): string {
  const wait = error.retryAfterSeconds !== undefined ? `${formatRetryAfter(error.retryAfterSeconds)} 후` : '잠시 후'
  return `실패한 로그인이 반복되었거나 동시에 처리 중인 로그인 요청이 많으면 제한됩니다. ${wait} 다시 시도하세요.`
}

export function describeError(error: unknown): ErrorView {
  if (error instanceof ApiError) {
    const body = error.body
    if (error.status === 429) {
      return {
        kind: 'other',
        title: MESSAGES.RATE_LIMIT_EXCEEDED,
        message: rateLimitDetail(error),
        details: [],
        traceId: body?.traceId ?? null,
      }
    }
    const kind: ErrorKind =
      error.status === 401 ? 'unauthorized' : error.status === 403 ? 'forbidden' : error.status === 404 ? 'notFound' : 'other'
    return {
      kind,
      title: (body && MESSAGES[body.code]) ?? `요청이 실패했습니다 (HTTP ${error.status}).`,
      message: body?.message ?? null,
      details: body?.details.map((d) => (d.field ? `${d.field}: ${d.reason}` : d.reason)) ?? [],
      traceId: body?.traceId ?? null,
    }
  }
  return {
    kind: 'network',
    title: '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인하세요.',
    message: null,
    details: [],
    traceId: null,
  }
}

/** 서버 검증 오류의 필드별 사유. 필드 이름의 마지막 경로(예: settings.shortsMinSeconds → shortsMinSeconds)로도 찾는다. */
export function fieldErrors(error: unknown): Record<string, string> {
  const result: Record<string, string> = {}
  if (error instanceof ApiError && error.body) {
    for (const detail of error.body.details) {
      if (detail.field) {
        result[detail.field] = detail.reason
        const last = detail.field.split('.').pop()
        if (last && !(last in result)) {
          result[last] = detail.reason
        }
      }
    }
  }
  return result
}

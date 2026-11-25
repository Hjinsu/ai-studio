import { ApiError, parseRetryAfter, readJson, toApiError } from './client'
import type { components } from './generated/schema'

/**
 * 관리자 API 공통 호출기 (T02_AUTH.md, ADR-037).
 * - 세션 쿠키 인증(same-origin), 변경 요청에는 CSRF 헤더(X-XSRF-TOKEN)를 붙인다.
 * - CSRF 토큰은 GET /api/v1/auth/csrf로 받아 메모리에만 둔다(쿠키·저장소에 두지 않음).
 * - 로그인·로그아웃 후 토큰이 바뀌므로 resetCsrf()로 버린다. 토큰이 만료돼 403이면 한 번만 다시 받아 재시도한다.
 * - 금액·환율은 문자열 그대로 다룬다(부동소수 변환 금지, ADR-035).
 */
type CsrfToken = components['schemas']['CsrfToken']

let csrf: CsrfToken | null = null

export function resetCsrf(): void {
  csrf = null
}

async function csrfToken(): Promise<CsrfToken> {
  if (csrf === null) {
    const response = await fetch('/api/v1/auth/csrf', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    const body = await readJson(response)
    if (!response.ok) {
      throw toApiError(response.status, body)
    }
    csrf = body as CsrfToken
  }
  return csrf
}

export type Query = Record<string, string | number | boolean | null | undefined>

export interface RequestOptions {
  query?: Query
  body?: unknown
  headers?: Record<string, string>
}

export function buildUrl(path: string, query?: Query): string {
  if (query === undefined) {
    return path
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params.append(key, String(value))
    }
  }
  const text = params.toString()
  return text === '' ? path : `${path}?${text}`
}

async function send(method: string, path: string, options: RequestOptions, withCsrf: boolean): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (withCsrf) {
    const token = await csrfToken()
    headers[token.headerName] = token.token
  }
  return fetch(buildUrl(path, options.query), {
    method,
    credentials: 'same-origin',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
}

export async function request<T>(method: 'GET' | 'POST' | 'PATCH', path: string, options: RequestOptions = {}): Promise<T> {
  const mutating = method !== 'GET'
  let response = await send(method, path, options, mutating)
  if (mutating && response.status === 403) {
    // 세션 교체 등으로 토큰이 바뀌었을 수 있다: 토큰을 다시 받아 한 번만 재시도(멱등 키는 호출자가 유지)
    resetCsrf()
    response = await send(method, path, options, true)
  }
  const body = await readJson(response)
  if (!response.ok) {
    throw toApiError(response.status, body, parseRetryAfter(response.headers.get('Retry-After')))
  }
  return body as T
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401
}

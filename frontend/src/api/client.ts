/** 백엔드 공통 오류 응답 (docs/04_API_SPEC.md, ADR-011) */
export interface ApiErrorBody {
  code: string
  message: string
  traceId: string
  timestamp: string
  details: { field: string | null; reason: string }[]
}

export class ApiError extends Error {
  readonly status: number
  readonly body: ApiErrorBody | undefined
  /** 429 응답의 Retry-After(초). 계약(OpenAPI TooManyRequests)상 정수 초. 없으면 undefined. */
  readonly retryAfterSeconds: number | undefined

  constructor(status: number, body: ApiErrorBody | undefined, retryAfterSeconds?: number) {
    super(body?.message ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.body = body
    this.retryAfterSeconds = retryAfterSeconds
  }
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  return typeof value === 'object' && value !== null && 'code' in value && 'traceId' in value
}

export async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (text === '') {
    return undefined
  }
  try {
    return JSON.parse(text) as unknown
  } catch {
    return undefined
  }
}

export function toApiError(status: number, body: unknown, retryAfterSeconds?: number): ApiError {
  return new ApiError(status, isApiErrorBody(body) ? body : undefined, retryAfterSeconds)
}

/** Retry-After 헤더의 정수 초만 해석한다(HTTP-date 형식은 무시). */
export function parseRetryAfter(value: string | null): number | undefined {
  if (value === null || !/^\d+$/.test(value.trim())) {
    return undefined
  }
  return Number(value.trim())
}

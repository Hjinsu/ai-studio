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

  constructor(status: number, body: ApiErrorBody | undefined) {
    super(body?.message ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.body = body
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

export function toApiError(status: number, body: unknown): ApiError {
  return new ApiError(status, isApiErrorBody(body) ? body : undefined)
}

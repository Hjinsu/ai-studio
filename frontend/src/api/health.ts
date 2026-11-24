import { readJson, toApiError } from './client'

export interface HealthStatus {
  status: string
}

/**
 * GET /actuator/health. 서버가 DOWN이면 503과 {"status":"DOWN"}을 반환하므로 오류가 아닌 상태로 다룬다.
 */
export async function fetchHealth(): Promise<HealthStatus> {
  const response = await fetch('/actuator/health', { headers: { Accept: 'application/json' } })
  const body = await readJson(response)
  if (typeof body === 'object' && body !== null && 'status' in body && typeof body.status === 'string') {
    return { status: body.status }
  }
  throw toApiError(response.status, body)
}

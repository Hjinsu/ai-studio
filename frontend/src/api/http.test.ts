import { describe, expect, it } from 'vitest'
import { ApiError } from './client'
import { buildUrl, request, resetCsrf } from './http'
import { csrf, error, mockApi, on } from '../test/mockApi'

describe('request', () => {
  it('does not send CSRF on GET and adds the token on mutating requests', async () => {
    const calls = mockApi(csrf, on('GET', '/api/x', 200, { ok: 1 }), on('POST', '/api/x', 200, { ok: 2 }))

    await request('GET', '/api/x')
    await request('POST', '/api/x', { body: { a: 1 } })
    await request('POST', '/api/x', { body: { a: 2 } })

    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'GET /api/x',
      'GET /api/v1/auth/csrf',
      'POST /api/x',
      'POST /api/x',
    ])
    expect(calls[0].headers['X-XSRF-TOKEN']).toBeUndefined()
    expect(calls[2].headers['X-XSRF-TOKEN']).toBe('tok-1')
    expect(calls[2].headers['Content-Type']).toBe('application/json')
    expect(calls[2].body).toEqual({ a: 1 })
  })

  it('refetches the CSRF token once and retries after a 403', async () => {
    let attempts = 0
    const calls = mockApi(csrf, (call) => {
      if (call.method === 'POST') {
        attempts += 1
        return attempts === 1 ? { status: 403, body: error('FORBIDDEN', 'csrf') } : { status: 200, body: {} }
      }
      return undefined
    })

    await request('POST', '/api/y', { headers: { 'Idempotency-Key': 'ui-key-1' } })

    expect(calls.filter((c) => c.url === '/api/v1/auth/csrf')).toHaveLength(2)
    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    // 재시도도 같은 멱등 키
    expect(posts.map((c) => c.headers['Idempotency-Key'])).toEqual(['ui-key-1', 'ui-key-1'])
  })

  it('does not retry a second 403 and surfaces the common error body', async () => {
    mockApi(csrf, on('POST', '/api/z', 403, error('FORBIDDEN', 'no')))

    const failure = request('POST', '/api/z')

    await expect(failure).rejects.toBeInstanceOf(ApiError)
    await expect(failure).rejects.toMatchObject({ status: 403, body: { code: 'FORBIDDEN', traceId: 'trace-1' } })
  })

  it('fetches a new CSRF token after reset (login/logout rotate it)', async () => {
    const calls = mockApi(csrf, on('POST', '/api/x', 200, {}))
    await request('POST', '/api/x')
    resetCsrf()
    await request('POST', '/api/x')
    expect(calls.filter((c) => c.url === '/api/v1/auth/csrf')).toHaveLength(2)
  })

  it('keeps money strings as-is (no float conversion)', async () => {
    mockApi(on('GET', '/api/m', 200, { amount: '1400.123457', big: '123456789012345678.000001' }))
    const body = await request<{ amount: string; big: string }>('GET', '/api/m')
    expect(body).toEqual({ amount: '1400.123457', big: '123456789012345678.000001' })
  })
})

describe('Retry-After', () => {
  it('keeps integer Retry-After seconds on 429 errors and ignores other formats', async () => {
    mockApi(
      csrf,
      (call) => (call.url === '/api/a' ? { status: 429, body: error('RATE_LIMIT_EXCEEDED', 'Too many requests.'), headers: { 'Retry-After': '900' } } : undefined),
      (call) => (call.url === '/api/b' ? { status: 429, body: error('RATE_LIMIT_EXCEEDED', 'Too many requests.'), headers: { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' } } : undefined),
    )
    await expect(request('POST', '/api/a')).rejects.toMatchObject({ status: 429, retryAfterSeconds: 900 })
    await expect(request('POST', '/api/b')).rejects.toMatchObject({ status: 429, retryAfterSeconds: undefined })
  })
})

describe('buildUrl', () => {
  it('drops empty query values', () => {
    expect(buildUrl('/a', { x: '', y: undefined, z: null, page: 0, s: 'A B' })).toBe('/a?page=0&s=A+B')
    expect(buildUrl('/a', {})).toBe('/a')
  })
})

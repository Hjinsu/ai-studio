import { render } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { vi } from 'vitest'
import { App } from '../App'
import { resetCsrf } from '../api/http'
import { createQueryClient } from '../queryClient'
import { routes } from '../routes'
import { ToastProvider } from '../ui/Toast'

export interface Call {
  method: string
  url: string
  headers: Record<string, string>
  body: unknown
}

type Handler = (call: Call) => { status: number; body?: unknown; headers?: Record<string, string> } | undefined

/** fetch 대역: 등록한 핸들러 순서대로 일치하는 첫 응답을 돌려주고 모든 호출을 기록한다. */
export function mockApi(...handlers: Handler[]) {
  const calls: Call[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call: Call = {
      method: init?.method ?? 'GET',
      url: String(input),
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined,
    }
    calls.push(call)
    for (const handler of handlers) {
      const result = handler(call)
      if (result) {
        return new Response(result.body === undefined ? '' : JSON.stringify(result.body), {
          status: result.status,
          headers: { 'Content-Type': 'application/json', ...result.headers },
        })
      }
    }
    return new Response(JSON.stringify(error('RESOURCE_NOT_FOUND', 'no mock')), { status: 404 })
  })
  vi.stubGlobal('fetch', fetchMock)
  resetCsrf()
  return calls
}

export const on =
  (method: string, path: string | RegExp, status: number, body?: unknown): Handler =>
  (call) => {
    const pathname = call.url.split('?')[0]
    const matches = typeof path === 'string' ? pathname === path : path.test(pathname)
    return call.method === method && matches ? { status, body } : undefined
  }

export function error(code: string, message: string) {
  return { code, message, traceId: 'trace-1', timestamp: '2026-11-25T00:00:00Z', details: [] }
}

export const csrf = on('GET', '/api/v1/auth/csrf', 200, { headerName: 'X-XSRF-TOKEN', token: 'tok-1' })
export const me = on('GET', '/api/v1/auth/me', 200, { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null })
export const health = on('GET', '/actuator/health', 200, { status: 'UP' })

export function renderApp(path = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const client = createQueryClient()
  const view = render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <App router={router} />
      </ToastProvider>
    </QueryClientProvider>,
  )
  return { router, client, unmount: view.unmount }
}

export const page = <T,>(items: T[]) => ({ page: 0, size: 20, totalElements: items.length, totalPages: 1, items })

export const channels = [
  { id: '5', name: 'science', language: 'en', timezone: 'America/New_York', status: 'ACTIVE', version: 3, currentConfigRevisionNo: 2, createdAt: '2026-11-25T00:00:00Z', updatedAt: '2026-11-25T00:00:00Z', archivedAt: null },
  { id: '6', name: 'paused-one', language: 'ko', timezone: 'Asia/Seoul', status: 'PAUSED', version: 1, currentConfigRevisionNo: 1, createdAt: '2026-11-25T00:00:00Z', updatedAt: '2026-11-25T00:00:00Z', archivedAt: null },
  { id: '7', name: 'old-archive', language: 'ko', timezone: 'Asia/Seoul', status: 'ARCHIVED', version: 2, currentConfigRevisionNo: 1, createdAt: '2026-11-25T00:00:00Z', updatedAt: '2026-11-25T00:00:00Z', archivedAt: '2026-11-25T00:00:00Z' },
]

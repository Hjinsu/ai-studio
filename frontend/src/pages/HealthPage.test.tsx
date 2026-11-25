import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { HealthPage } from './HealthPage'

function renderWithClient() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <HealthPage />
    </QueryClientProvider>,
  )
}

function stubFetch(status: number, body: string) {
  const fetchMock = vi.fn(async () => new Response(body, { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('HealthPage', () => {
  it('shows 정상 for UP from /actuator/health only', async () => {
    const fetchMock = stubFetch(200, '{"status":"UP"}')
    renderWithClient()
    await waitFor(() => expect(screen.getByTestId('health-status')).toHaveTextContent('정상'))
    expect(fetchMock).toHaveBeenCalledWith('/actuator/health', expect.anything())
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows 연결 실패 when the server reports DOWN (503)', async () => {
    stubFetch(503, '{"status":"DOWN"}')
    renderWithClient()
    await waitFor(() => expect(screen.getByTestId('health-status')).toHaveTextContent('연결 실패'))
    expect(screen.getByText('서버가 정상 상태가 아니라고 응답했습니다.')).toBeInTheDocument()
  })

  it('shows 연결 실패 when the server is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    renderWithClient()
    await waitFor(() => expect(screen.getByTestId('health-status')).toHaveTextContent('연결 실패'))
    expect(screen.getByText(/서버에 연결할 수 없습니다/)).toBeInTheDocument()
  })

  it('shows 확인 중 while pending', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
    renderWithClient()
    expect(screen.getByTestId('health-status')).toHaveTextContent('확인 중')
  })
})

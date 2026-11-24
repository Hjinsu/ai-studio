import { render, screen } from '@testing-library/react'
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
  it('shows UP status from /actuator/health', async () => {
    const fetchMock = stubFetch(200, '{"status":"UP"}')

    renderWithClient()

    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')
    expect(fetchMock).toHaveBeenCalledWith('/actuator/health', expect.anything())
  })

  it('shows DOWN status when server reports 503', async () => {
    stubFetch(503, '{"status":"DOWN"}')

    renderWithClient()

    expect(await screen.findByTestId('health-status')).toHaveTextContent('DOWN')
  })

  it('shows an alert when the server is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))

    renderWithClient()

    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결할 수 없습니다.')
  })

  it('shows an alert for a non-health error body', async () => {
    stubFetch(500, '{"code":"INTERNAL_SERVER_ERROR","message":"x","traceId":"t","timestamp":"2026-01-01T00:00:00Z","details":[]}')

    renderWithClient()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})

import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { csrf, error, me, mockApi, on, page, renderApp } from './test/mockApi'

const dashboard = on('GET', '/api/v1/dashboard/summary', 200, {
  channelCounts: { active: 2, paused: 1, archived: 0 },
  systemUsage: [
    {
      id: '1', policyId: '7', scope: 'SYSTEM', channelId: null, periodType: 'DAY',
      periodStart: '2026-11-24T15:00:00Z', periodEnd: '2026-11-25T15:00:00Z', timezone: 'Asia/Seoul',
      limitKrw: '10000.000000', reservedKrw: '1400.123457', spentKrw: '0.000001', remainingKrw: '-5.5',
    },
  ],
  reservationsRequiringReconciliation: 3,
  spendingEnabled: false,
  consistency: { status: 'OK', checkedAt: null, issueCount: 0 },
  generatedAt: '2026-11-25T00:00:00Z',
})

describe('App authentication', () => {
  it('shows the login page when not signed in, then the dashboard after login', async () => {
    let signedIn = false
    const calls = mockApi(
      csrf,
      (call) => (call.url === '/api/v1/auth/me'
        ? signedIn
          ? { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null } }
          : { status: 401, body: error('UNAUTHORIZED', 'Authentication required.') }
        : undefined),
      (call) => {
        if (call.method === 'POST' && call.url === '/api/v1/auth/login') {
          signedIn = true
          return { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null } }
        }
        return undefined
      },
      dashboard,
    )
    renderApp('/')

    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('아이디'), 'admin')
    await user.type(screen.getByLabelText('비밀번호'), 'correct-horse-battery-1')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { name: '대시보드' })).toBeInTheDocument()
    const loginCall = calls.find((c) => c.url === '/api/v1/auth/login')
    expect(loginCall?.headers['X-XSRF-TOKEN']).toBe('tok-1')
    expect(loginCall?.body).toEqual({ login: 'admin', password: 'correct-horse-battery-1' })
    // 금액 문자열 그대로, 음수 잔여 경고
    expect(screen.getByText('1400.123457 KRW')).toBeInTheDocument()
    expect(screen.getByText('-5.5 KRW')).toHaveClass('warn')
    expect(screen.getByText('차단')).toBeInTheDocument()
    expect(screen.getByText('3건')).toBeInTheDocument()
  })

  it('shows the server error for a failed login without revealing the reason', async () => {
    mockApi(
      csrf,
      on('GET', '/api/v1/auth/me', 401, error('UNAUTHORIZED', 'Authentication required.')),
      on('POST', '/api/v1/auth/login', 401, error('UNAUTHORIZED', 'Invalid login or password.')),
    )
    renderApp('/')
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('아이디'), 'admin')
    await user.type(screen.getByLabelText('비밀번호'), 'wrong')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Invalid login or password.')
    expect(alert).toHaveTextContent('trace-1')
  })

  it('returns to the login page when a later request gets 401 (session expired)', async () => {
    let expired = false
    mockApi(
      csrf,
      (call) => (call.url === '/api/v1/auth/me'
        ? expired
          ? { status: 401, body: error('UNAUTHORIZED', 'expired') }
          : { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null } }
        : undefined),
      (call) => {
        if (call.url.startsWith('/api/v1/channels')) {
          expired = true
          return { status: 401, body: error('UNAUTHORIZED', 'expired') }
        }
        return undefined
      },
    )
    renderApp('/channels')
    expect(await screen.findByRole('heading', { name: 'AI Studio 관리자 로그인' })).toBeInTheDocument()
  })

  it('logs out and shows the login page', async () => {
    let signedIn = true
    const calls = mockApi(
      csrf,
      (call) => (call.url === '/api/v1/auth/me'
        ? signedIn
          ? { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null } }
          : { status: 401, body: error('UNAUTHORIZED', 'x') }
        : undefined),
      (call) => {
        if (call.url === '/api/v1/auth/logout') {
          signedIn = false
          return { status: 204 }
        }
        return undefined
      },
      on('GET', '/api/v1/channels', 200, page([])),
    )
    renderApp('/channels')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '로그아웃' }))
    expect(await screen.findByRole('heading', { name: 'AI Studio 관리자 로그인' })).toBeInTheDocument()
    expect(calls.find((c) => c.url === '/api/v1/auth/logout')?.headers['X-XSRF-TOKEN']).toBe('tok-1')
  })

  it('renders the not-found page for unknown routes', async () => {
    mockApi(me)
    renderApp('/nope')
    await waitFor(() => expect(screen.getByRole('heading', { name: '페이지를 찾을 수 없습니다' })).toBeInTheDocument())
  })
})

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { csrf, error, health, me, mockApi, on, page, renderApp } from './test/mockApi'

const dashboard = on('GET', '/api/v1/dashboard/summary', 200, {
  channelCounts: { active: 2, paused: 1, archived: 0 },
  systemUsage: [
    {
      id: '1', policyId: '7', scope: 'SYSTEM', channelId: null, periodType: 'DAY',
      periodStart: '2026-11-24T15:00:00Z', periodEnd: '2026-11-25T15:00:00Z', timezone: 'Asia/Seoul',
      limitKrw: '10000.000000', reservedKrw: '1400.123457', spentKrw: '9000.5', remainingKrw: '-400.623457',
    },
    {
      id: null, policyId: '8', scope: 'SYSTEM', channelId: null, periodType: 'MONTH',
      periodStart: '2026-10-31T15:00:00Z', periodEnd: '2026-11-30T15:00:00Z', timezone: 'Asia/Seoul',
      limitKrw: '300000.000000', reservedKrw: '0', spentKrw: '0', remainingKrw: '300000.000000',
    },
  ],
  reservationsRequiringReconciliation: 3,
  spendingEnabled: false,
  consistency: { status: 'UNKNOWN', checkedAt: null, issueCount: 0 },
  generatedAt: '2026-11-25T00:00:00Z',
})

function signedInSwitch() {
  let signedIn = false
  return {
    set: (value: boolean) => (signedIn = value),
    handler: (call: { url: string }) =>
      call.url === '/api/v1/auth/me'
        ? signedIn
          ? { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null } }
          : { status: 401, body: error('UNAUTHORIZED', 'Authentication required.') }
        : undefined,
  }
}

describe('App authentication', () => {
  it('logs in with CSRF and shows the dashboard with exact money formatting', async () => {
    const session = signedInSwitch()
    const calls = mockApi(
      csrf,
      health,
      session.handler,
      (call) => {
        if (call.method === 'POST' && call.url === '/api/v1/auth/login') {
          session.set(true)
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

    expect(await screen.findByRole('heading', { name: '대시보드', level: 1 })).toBeInTheDocument()
    const loginCall = calls.find((c) => c.url === '/api/v1/auth/login')
    expect(loginCall?.headers['X-XSRF-TOKEN']).toBe('tok-1')
    expect(loginCall?.body).toEqual({ login: 'admin', password: 'correct-horse-battery-1' })
    // ₩ + 천 단위, 소수 정밀도 유지, 음수 잔여는 한도 초과
    expect(screen.getAllByText('₩10,000').length).toBeGreaterThan(0)
    expect(screen.getByText('₩1,400.123457', { selector: 'dd span' })).toBeInTheDocument()
    expect(screen.getByText('-₩400.623457')).toBeInTheDocument()
    expect(screen.getByText('한도 초과', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('지출 차단')).toBeInTheDocument()
    expect(screen.getByText('미검사')).toBeInTheDocument()
    expect(screen.getByText('3건')).toBeInTheDocument()
    // 아직 예약이 없는 기간(id null)은 0원으로 안내
    expect(screen.getByText(/아직 예약이 없어 사용 0원/)).toBeInTheDocument()
  })

  it('toggles password visibility and shows the server error once per submit', async () => {
    const calls = mockApi(csrf, on('GET', '/api/v1/auth/me', 401, error('UNAUTHORIZED', 'x')), (call) => {
      if (call.url === '/api/v1/auth/login') {
        return { status: 401, body: error('UNAUTHORIZED', 'Invalid login or password.') }
      }
      return undefined
    })
    renderApp('/')
    const user = userEvent.setup()
    const password = await screen.findByLabelText('비밀번호')
    expect(password).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: '비밀번호 표시' }))
    expect(password).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: '비밀번호 숨기기' })).toHaveAttribute('aria-pressed', 'true')
    await user.type(screen.getByLabelText('아이디'), 'admin')
    await user.type(password, 'wrong')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Invalid login or password.')
    expect(alert).toHaveTextContent('trace-1')
    expect(calls.filter((c) => c.url === '/api/v1/auth/login')).toHaveLength(1)
  })

  it('returns to login with a session-expired notice when a later request gets 401', async () => {
    let expired = false
    mockApi(
      csrf,
      health,
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
    expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeInTheDocument()
    expect(screen.getByText(/세션이 만료되었거나/)).toBeInTheDocument()
  })

  it('logs out with CSRF and shows the login page without the expiry notice', async () => {
    let signedIn = true
    const calls = mockApi(
      csrf,
      health,
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
    expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeInTheDocument()
    expect(screen.queryByText(/세션이 만료되었거나/)).not.toBeInTheDocument()
    expect(calls.find((c) => c.url === '/api/v1/auth/logout')?.headers['X-XSRF-TOKEN']).toBe('tok-1')
  })

  it('shows grouped navigation with the current item and the not-found page', async () => {
    mockApi(me, health)
    renderApp('/nope')
    await waitFor(() => expect(screen.getByText('페이지를 찾을 수 없습니다')).toBeInTheDocument())
    const nav = screen.getAllByRole('navigation', { name: '주 메뉴' })[0]
    for (const name of ['대시보드', '채널 관리', '예산 정책', '예산 사용량', '지출 제어', '예약·정산', '비용 원장', '환율 관리', '서버 상태']) {
      expect(within(nav).getByRole('link', { name })).toBeInTheDocument()
    }
    expect(within(nav).getByText('비용·예산')).toBeInTheDocument()
  })

  it('toggles dark mode and persists the choice', async () => {
    mockApi(me, health, dashboard)
    document.documentElement.classList.remove('dark')
    renderApp('/')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '다크 모드' }))
    expect(document.documentElement).toHaveClass('dark')
    expect(localStorage.getItem('ai-studio.theme')).toBe('dark')
    await user.click(screen.getByRole('button', { name: '라이트 모드' }))
    expect(document.documentElement).not.toHaveClass('dark')
    expect(localStorage.getItem('ai-studio.theme')).toBe('light')
  })
})

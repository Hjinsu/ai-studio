import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { csrf, error, health, me, mockApi, on, page, renderApp } from './test/mockApi'

const dashboardBody = {
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
}
const dashboard = on('GET', '/api/v1/dashboard/summary', 200, dashboardBody)

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
    // 관리자 차단 → 신규 지출 차단(UI-RV-01)
    expect(screen.getByText(/관리자 차단/)).toBeInTheDocument()
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
    // 로그인 자격 증명 401은 폼 오류일 뿐 세션 만료로 처리하지 않는다(UI-RV-02)
    expect(screen.queryByText(/세션이 만료되었거나/)).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '관리자 로그인' })).toBeInTheDocument()
  })

  it('explains a login 429 neutrally (failures or concurrent requests) with the Retry-After wait', async () => {
    let retryAfter = '900'
    mockApi(csrf, on('GET', '/api/v1/auth/me', 401, error('UNAUTHORIZED', 'x')), (call) =>
      call.url === '/api/v1/auth/login'
        ? { status: 429, body: error('RATE_LIMIT_EXCEEDED', 'Too many requests.'), headers: { 'Retry-After': retryAfter } }
        : undefined,
    )
    renderApp('/')
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('아이디'), 'admin')
    await user.type(screen.getByLabelText('비밀번호'), 'whatever-password')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('요청이 일시적으로 제한되었습니다.')
    expect(alert).toHaveTextContent('실패한 로그인이 반복되었거나 동시에 처리 중인 로그인 요청이 많으면 제한됩니다.')
    expect(alert).toHaveTextContent('약 15분 후 다시 시도하세요.')
    retryAfter = '1'
    await user.click(screen.getByRole('button', { name: '로그인' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('1초 후 다시 시도하세요.'))
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

  it('a protected mutation 401 returns to login, drops CSRF and caches, and is not retried after re-login (UI-RV-02)', async () => {
    let signedIn = true
    let csrfIssued = 0
    const calls = mockApi(
      (call) => {
        if (call.url !== '/api/v1/auth/csrf') return undefined
        csrfIssued += 1
        return { status: 200, body: { headerName: 'X-XSRF-TOKEN', token: `tok-${csrfIssued}` } }
      },
      health,
      (call) => (call.url === '/api/v1/auth/me'
        ? signedIn
          ? { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null } }
          : { status: 401, body: error('UNAUTHORIZED', 'expired') }
        : undefined),
      // 세션이 끝난 뒤의 보호 API는 모두 401(실제 서버와 같음)
      (call) => (!signedIn && call.url.startsWith('/api/v1/') && !call.url.startsWith('/api/v1/auth/')
        ? { status: 401, body: error('UNAUTHORIZED', 'expired') }
        : undefined),
      (call) => {
        if (call.method === 'POST' && call.url === '/api/v1/budget/consistency-checks') {
          signedIn = false // 서버에서 세션이 끝남
          return { status: 401, body: error('UNAUTHORIZED', 'expired') }
        }
        if (call.method === 'POST' && call.url === '/api/v1/auth/login') {
          signedIn = true
          return { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null } }
        }
        return undefined
      },
      on('GET', '/api/v1/budget/control', 200, {
        spendingEnabled: false, spendingChangedAt: null, spendingChangedBy: null, spendingChangeReason: null,
        consistency: { status: 'UNKNOWN', checkedAt: null, issueCount: 0 }, version: 1, updatedAt: '2026-11-25T00:00:00Z',
      }),
      on('GET', '/api/v1/budget-policies', 200, page([])),
    )
    const { client } = renderApp('/budget/control')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '지금 검사' }))

    expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeInTheDocument()
    expect(screen.getByText(/세션이 만료되었거나/)).toBeInTheDocument()
    // 보호 화면 데이터와 실패한 변경 요청은 남기지 않는다
    await waitFor(() =>
      expect(client.getQueryCache().findAll().filter((q) => q.queryKey[0] !== 'auth' && q.state.data !== undefined)).toHaveLength(0),
    )
    expect(client.getMutationCache().getAll()).toHaveLength(0)

    // 다시 로그인: 이전 세션의 CSRF 토큰을 버리고 새로 받는다. 실패한 검사 요청은 자동으로 다시 보내지 않는다.
    await user.type(screen.getByLabelText('아이디'), 'admin')
    await user.type(screen.getByLabelText('비밀번호'), 'correct-horse-battery-1')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByRole('heading', { name: '지출 제어', level: 1 })).toBeInTheDocument()
    expect(calls.find((c) => c.url === '/api/v1/auth/login')?.headers['X-XSRF-TOKEN']).toBe('tok-2')
    expect(calls.filter((c) => c.url === '/api/v1/budget/consistency-checks')).toHaveLength(1)
    // 만료 전환은 한 번만(로그인 상태 재조회가 반복되지 않음)
    const meCallsBeforeLogin = calls.findIndex((c) => c.url === '/api/v1/auth/login')
    expect(calls.slice(0, meCallsBeforeLogin).filter((c) => c.url === '/api/v1/auth/me').length).toBeLessThanOrEqual(3)
  })

  it('shows new spending as blocked when consistency is ERROR even if the admin allowed it (UI-RV-01)', async () => {
    const allowedButError = (call: { url: string }) =>
      call.url === '/api/v1/dashboard/summary'
        ? { status: 200, body: { ...dashboardBody, spendingEnabled: true, consistency: { status: 'ERROR', checkedAt: '2026-11-25T00:00:00Z', issueCount: 2 } } }
        : undefined
    mockApi(me, health, allowedButError)
    renderApp('/')
    expect(await screen.findByText(/정합성 오류로 차단\(관리자 설정은 허용\)/)).toBeInTheDocument()
    expect(screen.getByText('차단')).toBeInTheDocument()
    expect(screen.queryByText('관리자 허용')).not.toBeInTheDocument()
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

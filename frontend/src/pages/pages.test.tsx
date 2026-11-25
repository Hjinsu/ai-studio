import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { logout } from '../api/admin'
import { ME_KEY } from '../queryClient'
import { channels, csrf, error, health, me, mockApi, on, page, renderApp } from '../test/mockApi'

const channel = channels[0]
const config = {
  defaultContentType: 'LONGFORM', shortsMinSeconds: 40, shortsMaxSeconds: 60, longformMinSeconds: 480,
  longformMaxSeconds: 720, contentStrategy: 'Explain', targetAudience: null, narrationStyle: null, visualStyle: null,
  syntheticDisclosureDefault: true, providerOptions: null, channelId: '5', revisionNo: 2, changeNote: 'longer',
  createdBy: '1', createdAt: '2026-11-25T00:00:00Z',
}
const allChannels = on('GET', '/api/v1/channels', 200, { ...page(channels), size: 100 })

describe('Channels', () => {
  it('lists channels with Korean status badges and creates a channel with grouped settings', async () => {
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', /^\/api\/v1\/channels\/\d+\/config$/, 200, config),
      on('POST', '/api/v1/channels', 201, { ...channel, id: '9', name: 'new' }),
      on('GET', '/api/v1/channels/9', 200, { ...channel, id: '9', name: 'new' }),
    )
    renderApp('/channels')
    const table = await screen.findByRole('table', { name: '채널 목록' })
    expect(await within(table).findByText('운영 중')).toBeInTheDocument()
    expect(within(table).getByText('일시정지')).toBeInTheDocument()
    expect(within(table).getByText('보관됨')).toBeInTheDocument()
    expect((await within(table).findAllByText('롱폼')).length).toBeGreaterThan(0)
    expect(within(table).getByText('영어(en)')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '채널 등록' }))
    const dialog = await screen.findByRole('dialog', { name: '채널 등록' })
    expect(within(dialog).getByText('콘텐츠 방향')).toBeInTheDocument()
    expect(within(dialog).getByText('영상 길이')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('채널명'), 'new')
    const tz = within(dialog).getByLabelText('시간대')
    await user.clear(tz)
    await user.type(tz, 'Asia/Seoul')
    // 클라이언트 검증: 최소 > 최대
    await user.clear(within(dialog).getByLabelText('쇼츠 최소 길이(초)'))
    await user.type(within(dialog).getByLabelText('쇼츠 최소 길이(초)'), '90')
    await user.click(within(dialog).getByRole('button', { name: '채널 등록' }))
    expect(await within(dialog).findByText('최대 길이는 최소 길이보다 짧을 수 없습니다.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)

    await user.clear(within(dialog).getByLabelText('쇼츠 최소 길이(초)'))
    await user.type(within(dialog).getByLabelText('쇼츠 최소 길이(초)'), '40')
    await user.click(within(dialog).getByRole('button', { name: '채널 등록' }))
    expect(await screen.findByRole('heading', { name: 'new' })).toBeInTheDocument()
    const body = calls.find((c) => c.method === 'POST')?.body as Record<string, unknown>
    expect(body).toMatchObject({ name: 'new', language: 'ko', timezone: 'Asia/Seoul' })
    expect(body.initialConfig).toMatchObject({ defaultContentType: 'SHORTS', shortsMinSeconds: 40, syntheticDisclosureDefault: true })
  })

  it('archives only after confirmation and shows conflicts inside the dialog', async () => {
    const calls = mockApi(
      me, csrf, health,
      on('GET', '/api/v1/channels/5', 200, channel),
      on('POST', '/api/v1/channels/5/archive', 409, error('RESOURCE_CONFLICT', 'The channel was changed.')),
    )
    renderApp('/channels/5')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '보관' }))
    const dialog = await screen.findByRole('alertdialog', { name: '채널을 보관할까요?' })
    expect(dialog).toHaveTextContent('되돌릴 수 없습니다')
    expect(calls.some((c) => c.url.endsWith('/archive'))).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: '보관' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('충돌')
    expect(calls.find((c) => c.url.endsWith('/archive'))?.body).toEqual({ version: 3 })
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
  })

  it('RVX-02-CB keeps the channel list after a held creation callback and re-login', async () => {
    let signedIn = true
    const created = { ...channel, id: '9', name: 'new' }
    const calls = mockApi(csrf, health, allChannels, (call) => {
      if (call.url === '/api/v1/auth/me') {
        return signedIn ? me(call) : { status: 401, body: error('UNAUTHORIZED', 'signed out') }
      }
      if (call.url === '/api/v1/auth/logout') {
        signedIn = false
        return { status: 200 }
      }
      if (call.url === '/api/v1/auth/login') {
        signedIn = true
        return me({ ...call, method: 'GET', url: '/api/v1/auth/me' })
      }
    }, on('POST', '/api/v1/channels', 201, created),
    on('GET', '/api/v1/channels/9', 200, created),
    on('GET', /^\/api\/v1\/channels\/\d+\/config$/, 200, config))
    const { client, router } = renderApp('/channels')
    const invalidate = client.invalidateQueries.bind(client)
    let release!: () => void
    let started!: () => void
    const sent = new Promise<void>((resolve) => { started = resolve })
    const held = new Promise<void>((resolve) => { release = resolve })
    vi.spyOn(client, 'invalidateQueries').mockImplementationOnce(async (filters, options) => {
      await invalidate(filters, options)
      started()
      await held
    })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '채널 등록' }))
    const dialog = within(await screen.findByRole('dialog', { name: '채널 등록' }))
    await user.type(dialog.getByLabelText('채널명'), 'new')
    await user.click(dialog.getByRole('button', { name: '채널 등록' }))
    await sent
    const oldMutation = client.getMutationCache().getAll()[0]
    expect(oldMutation.state.status).toBe('pending')
    await act(async () => {
      await logout()
      await client.resetQueries({ queryKey: ME_KEY })
    })
    await user.type(await screen.findByLabelText('아이디'), 'admin')
    await user.type(screen.getByLabelText('비밀번호'), 'fixture-password')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    await screen.findByRole('table', { name: '채널 목록' })
    await act(async () => release())
    await waitFor(() => expect(oldMutation.state.status).toBe('success'))
    expect(router.state.location.pathname).toBe('/channels')
    expect(screen.queryByText('채널 "new"을 등록했습니다.')).not.toBeInTheDocument()
    expect(calls.some((call) => call.url === '/api/v1/channels/9')).toBe(false)
  })

  it('shows revision history with changes and saves a new revision from the current one', async () => {
    const r1 = { ...config, revisionNo: 1, longformMaxSeconds: 600, changeNote: 'initial' }
    const calls = mockApi(
      me, csrf, health,
      on('GET', '/api/v1/channels/5', 200, channel),
      on('GET', '/api/v1/channels/5/config', 200, config),
      on('GET', '/api/v1/channels/5/config/revisions', 200, page([config, r1])),
      on('POST', '/api/v1/channels/5/config/revisions', 201, { ...config, revisionNo: 3 }),
    )
    renderApp('/channels/5?tab=history')
    const history = await screen.findByRole('list', { name: '채널 설정 이력' })
    expect(within(history).getByText('롱폼 길이')).toBeInTheDocument()
    expect(within(history).getByText('8분–10분')).toBeInTheDocument()
    expect(within(history).getByText('8분–12분')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('tab', { name: '채널 설정' }))
    await user.click(await screen.findByRole('button', { name: '새 설정 버전' }))
    const dialog = await screen.findByRole('dialog')
    const save = within(dialog).getByRole('button', { name: '설정 버전 저장' })
    expect(save).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/AI 서비스 설정/), '"x"')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(save)
    expect(await within(dialog).findByText(/JSON 객체 형식이어야 합니다/)).toBeInTheDocument()
    await user.clear(within(dialog).getByLabelText(/AI 서비스 설정/))
    await user.type(within(dialog).getByLabelText(/변경 메모/), 'no disclosure')
    await user.click(save)
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      baseRevisionNo: 2,
      changeNote: 'no disclosure',
      settings: {
        defaultContentType: 'LONGFORM', shortsMinSeconds: 40, shortsMaxSeconds: 60, longformMinSeconds: 480,
        longformMaxSeconds: 720, contentStrategy: 'Explain', targetAudience: null, narrationStyle: null,
        visualStyle: null, syntheticDisclosureDefault: false, providerOptions: null,
      },
    })
  })
})

describe('Budget policies', () => {
  it('creates a CHANNEL policy by channel name, excluding archived channels, with a string limit', async () => {
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/budget-policies', 200, page([])),
      on('POST', '/api/v1/budget-policies', 201, { id: '9' }),
      on('GET', '/api/v1/budget-policies/9', 404, error('RESOURCE_NOT_FOUND', 'x')),
    )
    renderApp('/budget/policies')
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: '정책 만들기' }))[0])
    const dialog = await screen.findByRole('dialog', { name: '예산 정책 만들기' })
    await user.click(within(dialog).getByText('채널별'))
    const select = within(dialog).getByLabelText('채널')
    await waitFor(() => expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['채널 선택', 'science', 'paused-one (일시정지)']))
    await user.selectOptions(select, '5')
    await user.type(within(dialog).getByLabelText('한도'), '30,000.000001')
    expect(within(dialog).getByText('₩30,000.000001')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '정책 만들기' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      scope: 'CHANNEL', channelId: '5', periodType: 'DAY', limitKrw: '30000.000001', timezone: 'Asia/Seoul',
    })
  })

  it('warns that a zero limit blocks everything and requires a channel', async () => {
    const calls = mockApi(me, csrf, health, allChannels, on('GET', '/api/v1/budget-policies', 200, page([])))
    renderApp('/budget/policies')
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: '정책 만들기' }))[0])
    const dialog = await screen.findByRole('dialog', { name: '예산 정책 만들기' })
    await user.click(within(dialog).getByText('채널별'))
    await user.type(within(dialog).getByLabelText('한도'), '0')
    expect(within(dialog).getByText('전면 차단')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '정책 만들기' }))
    expect(await within(dialog).findByText('채널을 선택하세요.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })
})

describe('Spending control and consistency', () => {
  const control = (enabled: boolean, version: number) => ({
    spendingEnabled: enabled, spendingChangedAt: null, spendingChangedBy: null, spendingChangeReason: null,
    consistency: { status: 'UNKNOWN', checkedAt: null, issueCount: 0 }, version, updatedAt: '2026-11-25T00:00:00Z',
  })

  it('RVX-02-CB cancels the started refresh and removes the old notification on re-login', async () => {
    let signedIn = true
    let version = 1
    const calls = mockApi(csrf, health, (call) => {
      if (call.url === '/api/v1/auth/me') {
        return signedIn ? me(call) : { status: 401, body: error('UNAUTHORIZED', 'signed out') }
      }
      if (call.url === '/api/v1/auth/logout') {
        signedIn = false
        return { status: 200 }
      }
      if (call.url === '/api/v1/auth/login') {
        signedIn = true
        version = 2
        return me({ ...call, method: 'GET', url: '/api/v1/auth/me' })
      }
      if (call.url === '/api/v1/budget/control') return { status: 200, body: control(false, version) }
    }, on('GET', '/api/v1/budget-policies', 200, page([])),
    on('POST', '/api/v1/budget/consistency-checks', 200, { consistent: true, issues: [] }))
    const routedFetch = globalThis.fetch
    let reads = 0
    let release!: (response: Response) => void
    let started!: () => void
    const sent = new Promise<void>((resolve) => { started = resolve })
    const held = new Promise<Response>((resolve) => { release = resolve })
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/v1/budget/control' && ++reads === 2) {
        started()
        return held
      }
      return routedFetch(input, init)
    })
    const { client, router } = renderApp('/budget/control')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '지금 검사' }))
    await sent
    expect(await screen.findByText('정합성 검사: 정상')).toBeInTheDocument()
    const oldQuery = client.getQueryCache().find({ queryKey: ['budget-control'] })!
    expect(oldQuery.state.fetchStatus).toBe('fetching')
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await user.type(await screen.findByLabelText('아이디'), 'admin')
    await user.type(screen.getByLabelText('비밀번호'), 'fixture-password')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    await screen.findByRole('button', { name: '지금 검사' })
    await waitFor(() => expect(client.getQueryData(['budget-control'])).toEqual(control(false, 2)))
    await act(async () => release(Response.json(control(true, 99))))
    expect(oldQuery.state.fetchStatus).toBe('idle')
    expect(client.getQueryData(['budget-control'])).toEqual(control(false, 2))
    expect(client.getQueryState(['budget-control'])?.isInvalidated).toBe(false)
    expect(screen.queryByText('정합성 검사: 정상')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/budget/control')
    expect(calls.filter((call) => call.url === '/api/v1/budget/consistency-checks')).toHaveLength(1)
  })

  it('allowing spending is a risk action that needs a reason and sends the control version', async () => {
    const calls = mockApi(
      me, csrf, health,
      on('GET', '/api/v1/budget/control', 200, control(false, 4)),
      on('GET', '/api/v1/budget-policies', 200, page([])),
      on('POST', '/api/v1/budget/control/spending', 200, control(true, 5)),
    )
    renderApp('/budget/control')
    expect(await screen.findByRole('heading', { name: '신규 지출 차단됨' })).toBeInTheDocument()
    expect(screen.getByText('시스템 전체 일간 정책 활성')).toBeInTheDocument()
    expect(screen.getByText('미검사')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '지출 허용…' }))
    const dialog = await screen.findByRole('alertdialog', { name: '신규 지출을 허용할까요?' })
    const confirm = within(dialog).getByRole('button', { name: '지출 허용' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText('변경 사유(필수, 감사 기록)'), 'test window')
    await user.click(confirm)
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ enabled: true, reason: 'test window', version: 4 })
    expect(await screen.findByText('신규 지출을 허용했습니다.')).toBeInTheDocument()
  })

  it.each([
    [false, 'OK', '신규 지출 차단됨', '관리자 설정 차단 · 정합성 정상'],
    [false, 'ERROR', '신규 지출 차단됨', '관리자 설정 차단 · 정합성 오류'],
    [true, 'OK', '관리자 설정: 신규 지출 허용', '관리자 설정 허용 · 정합성 정상'],
    [true, 'ERROR', '신규 지출 차단됨', '관리자 설정 허용 · 정합성 오류'],
    [true, 'UNKNOWN', '관리자 설정: 신규 지출 허용', '관리자 설정 허용 · 정합성 미검사'],
  ])('admin %s + consistency %s → %s (UI-RV-01)', async (enabled, status, heading, line) => {
    const calls = mockApi(
      me, csrf, health,
      on('GET', '/api/v1/budget/control', 200, { ...control(enabled, 2), consistency: { status, checkedAt: null, issueCount: status === 'ERROR' ? 1 : 0 } }),
      on('GET', '/api/v1/budget-policies', 200, page([])),
    )
    renderApp('/budget/control')
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(screen.getByText(line)).toBeInTheDocument()
    if (heading === '신규 지출 차단됨') {
      expect(screen.getByText(/이미 잡힌 예약의 정산과 조회는 계속할 수 있습니다/)).toBeInTheDocument()
    }
    if (status === 'UNKNOWN') expect(screen.getByText(/정합성 검사를 아직 하지 않았습니다/)).toBeInTheDocument()
    // 표시만 바꾸며 관리자 설정을 자동으로 바꾸지 않는다
    expect(screen.getByRole('button', { name: enabled ? '긴급 지출 차단' : '지출 허용…' })).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('shows consistency issues in Korean with the raw code', async () => {
    mockApi(
      me, csrf, health,
      on('GET', '/api/v1/budget/control', 200, control(false, 0)),
      on('GET', '/api/v1/budget-policies', 200, page([])),
      on('POST', '/api/v1/budget/consistency-checks', 200, {
        checkedAt: '2026-11-25T00:00:00Z', consistent: false,
        issues: [{ checkCode: 'BUCKET_SPENT_MISMATCH', entityType: 'BUCKET', entityId: '3', expected: '1', actual: '2' }],
      }),
    )
    renderApp('/budget/control')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '지금 검사' }))
    const table = await screen.findByRole('table', { name: '정합성 오류' })
    expect(within(table).getByText('기간 예산의 지출액이 연결된 원장 합계와 다릅니다.')).toBeInTheDocument()
    expect(within(table).getByText('BUCKET_SPENT_MISMATCH')).toBeInTheDocument()
    expect(within(table).getByText('기간 예산 #3')).toBeInTheDocument()
  })

  it('shows the reservation bucket link mismatch in Korean with its explanation', async () => {
    mockApi(
      me, csrf, health,
      on('GET', '/api/v1/budget/control', 200, control(false, 0)),
      on('GET', '/api/v1/budget-policies', 200, page([])),
      on('POST', '/api/v1/budget/consistency-checks', 200, {
        checkedAt: '2026-11-25T00:00:00Z', consistent: false,
        issues: [{
          checkCode: 'RESERVATION_BUCKET_LINK_MISMATCH', entityType: 'RESERVATION', entityId: '41',
          expected: 'SYSTEM DAY=1, SYSTEM MONTH=1, CHANNEL DAY<=1, CHANNEL MONTH<=1, invalid=0',
          actual: 'SYSTEM DAY=0, SYSTEM MONTH=1, CHANNEL DAY=0, CHANNEL MONTH=0, invalid=0',
        }],
      }),
    )
    renderApp('/budget/control')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '지금 검사' }))
    const table = await screen.findByRole('table', { name: '정합성 오류' })
    expect(within(table).getByText('예산 예약 연결 불일치')).toBeInTheDocument()
    expect(within(table).getByText('비용 예약에 필요한 예산 연결이 누락되었거나 올바르지 않습니다. 신규 비용 예약이 차단되었습니다.')).toBeInTheDocument()
    expect(within(table).getByText('RESERVATION_BUCKET_LINK_MISMATCH')).toBeInTheDocument()
    expect(within(table).getByText('예약 #41')).toBeInTheDocument()
  })
})

const reservation = {
  id: '11', requestKey: 'req-key-1', channelId: '5', purpose: 'image', originalCurrency: 'USD',
  estimateOriginal: '1.00', exchangeRateId: '2', fxRate: '1400.1234567891', estimateKrw: '1400.123457',
  status: 'RECONCILIATION_REQUIRED', reservedRemainingKrw: '1400.123457', recognizedCostKrw: '0',
  recognizedCostOriginal: '0', reconciliationReason: 'timeout', createdAt: '2026-11-25T00:00:00Z',
  updatedAt: '2026-11-25T00:00:00Z', closedAt: null, bucketIds: ['1', '2'],
  events: [
    { id: '1', eventKey: 'e1', reservationId: '11', requestKey: 'req-key-1', entryType: 'RESERVE', reasonCode: null, originalCurrency: 'USD', originalAmount: '1.00', fxRate: '1400.1234567891', exchangeRateId: '2', amountKrw: '1400.123457', reserveDeltaKrw: '1400.123457', costDeltaKrw: '0', costDeltaOriginal: '0', priceVersion: null, externalRef: null, actorType: 'SYSTEM', actorUserId: null, occurredAt: '2026-11-25T00:00:00Z', recordedAt: '2026-11-25T00:00:00Z' },
  ],
}

describe('Reservations and settlement', () => {
  it('highlights reconciliation, shows a ledger timeline and reuses one idempotency key on retry', async () => {
    let attempts = 0
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/budget-reservations/11', 200, reservation),
      (call) => {
        if (call.method === 'POST' && call.url.endsWith('/resolve')) {
          attempts += 1
          return attempts === 1
            ? { status: 500, body: error('INTERNAL_SERVER_ERROR', 'temporary') }
            : { status: 200, body: { ...reservation, status: 'SETTLED' } }
        }
        return undefined
      },
    )
    renderApp('/budget/reservations/11')
    expect(await screen.findByText(/외부 결과를 확인하지 못했습니다/)).toBeInTheDocument()
    const timeline = screen.getByRole('list', { name: '원장 이벤트' })
    expect(within(timeline).getByText('예약')).toBeInTheDocument()
    expect(within(timeline).getByText('+₩1,400.123457')).toBeInTheDocument()
    expect(await screen.findByText(/science/)).toBeInTheDocument()

    const user = userEvent.setup()
    await user.type(screen.getByLabelText('확정 총액(USD)'), '1.20')
    await user.type(screen.getByLabelText('판단 근거(필수, 감사 기록)'), 'invoice')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('1.2 USD')
    await user.click(within(dialog).getByRole('button', { name: '원장에 기록' }))
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '원장에 기록' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())

    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[0].headers['Idempotency-Key']).toMatch(/^ui-[0-9a-f-]{36}$/)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect(posts[0].body).toEqual({ outcome: 'CHARGED', note: 'invoice', finalAmountOriginal: '1.20' })
  })

  it('late charge on a released reservation is CHARGED only and needs an external reference', async () => {
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/budget-reservations/11', 200, { ...reservation, status: 'RELEASED' }),
      on('POST', '/api/v1/budget-reservations/11/resolve', 200, { ...reservation, status: 'SETTLED' }),
    )
    renderApp('/budget/reservations/11')
    expect(await screen.findByRole('heading', { name: '지연 과금 확정' })).toBeInTheDocument()
    expect(screen.queryByLabelText('결과')).not.toBeInTheDocument()
    expect(screen.getByLabelText('외부 근거(필수)')).toBeRequired()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('확정 총액(USD)'), '0.5')
    await user.type(screen.getByLabelText('외부 근거(필수)'), 'inv-9')
    await user.type(screen.getByLabelText('판단 근거(필수, 감사 기록)'), 'late invoice')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: '원장에 기록' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      outcome: 'CHARGED', note: 'late invoice', finalAmountOriginal: '0.5', externalRef: 'inv-9',
    })
  })

  it('adjusts a settled reservation in KRW basis with an explanation', async () => {
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/budget-reservations/11', 200, { ...reservation, status: 'SETTLED' }),
      on('POST', '/api/v1/budget-reservations/11/adjustments', 200, { ...reservation, status: 'SETTLED' }),
    )
    renderApp('/budget/reservations/11')
    const user = userEvent.setup()
    await user.selectOptions(await screen.findByLabelText('기준 통화'), 'KRW')
    expect(screen.getByText(/USD 기준 비용을 바꾸지 않습니다/)).toBeInTheDocument()
    await user.clear(screen.getByLabelText('사유 코드'))
    await user.type(screen.getByLabelText('사유 코드'), 'fx_difference')
    await user.type(screen.getByLabelText('메모(필수, 감사 기록)'), 'card rate')
    // 원화 기준은 소수 6자리까지(서버 검증과 동일)
    await user.type(screen.getByLabelText('금액(KRW)'), '-50.1234567')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    expect(await screen.findByText(/소수는 최대 6자리입니다/)).toBeInTheDocument()
    await user.clear(screen.getByLabelText('금액(KRW)'))
    await user.type(screen.getByLabelText('금액(KRW)'), '-50')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: '원장에 기록' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    const post = calls.find((c) => c.method === 'POST')!
    expect(post.body).toEqual({ entryType: 'ADJUSTMENT', basis: 'KRW', amount: '-50', reasonCode: 'FX_DIFFERENCE', note: 'card rate' })
    expect(post.headers['Idempotency-Key']).toBeDefined()
  })
})

describe('Ledger order and lost settlement responses', () => {
  it('RVX-02-CB cancels the failure callback lookup before its recorded result can affect re-login', async () => {
    let signedIn = true
    const detail = '/api/v1/budget-reservations/11'
    const settled = { ...reservation, status: 'SETTLED' }
    const calls = mockApi(csrf, health, allChannels, (call) => {
      if (call.url === '/api/v1/auth/me') {
        return signedIn ? me(call) : { status: 401, body: error('UNAUTHORIZED', 'signed out') }
      }
      if (call.url === '/api/v1/auth/logout') {
        signedIn = false
        return { status: 200 }
      }
      if (call.url === '/api/v1/auth/login') {
        signedIn = true
        return me({ ...call, method: 'GET', url: '/api/v1/auth/me' })
      }
    }, on('GET', detail, 200, settled),
    on('POST', `${detail}/adjustments`, 502, error('INTERNAL_SERVER_ERROR', 'lost')))
    const routedFetch = globalThis.fetch
    let reads = 0
    let release!: (response: Response) => void
    let started!: () => void
    const sent = new Promise<void>((resolve) => { started = resolve })
    const held = new Promise<Response>((resolve) => { release = resolve })
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === detail && ++reads === 2) {
        started()
        return held
      }
      return routedFetch(input, init)
    })
    const { client, router } = renderApp('/budget/reservations/11')
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('금액(USD)'), '0.1')
    await user.type(screen.getByLabelText('메모(필수, 감사 기록)'), 'fixture')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: '원장에 기록' }))
    await sent
    const post = calls.find((call) => call.url.endsWith('/adjustments'))!
    const lookup = client.getQueryCache().find({ queryKey: ['reservation-check', '11', post.headers['Idempotency-Key']] })!
    expect(lookup.state.fetchStatus).toBe('fetching')
    // The modal traps focus. Run the same logout and auth refresh without bypassing the UI focus trap.
    await act(async () => {
      await logout()
      await client.resetQueries({ queryKey: ME_KEY })
    })
    await user.type(await screen.findByLabelText('아이디'), 'admin')
    await user.type(screen.getByLabelText('비밀번호'), 'fixture-password')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    await screen.findByLabelText('금액(USD)')
    await act(async () => release(Response.json({
      ...settled, events: [{ ...reservation.events[0], eventKey: post.headers['Idempotency-Key'] }],
    })))
    expect(lookup.state.fetchStatus).toBe('idle')
    expect(client.getQueryData(['reservation', '11'])).toEqual(settled)
    expect(client.getQueryState(['reservation', '11'])?.isInvalidated).toBe(false)
    expect(screen.queryByText(/원장에 이미 기록된 것을 확인/)).not.toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/budget/reservations/11')
    expect(calls.filter((call) => call.url.endsWith('/adjustments'))).toHaveLength(1)
  })
  it('keeps the server ledger order and BIGINT ids exactly (UI-RV-05)', async () => {
    const base = reservation.events[0]
    const events = [
      // 서버 순서(원장 id 오름차순). 시각 표기가 서로 다르다(정수 초 / 소수초 / 같은 시각).
      { ...base, id: '9007199254740992', eventKey: 'k1', entryType: 'RESERVE', occurredAt: '2026-11-25T00:00:00Z', recordedAt: '2026-11-25T00:00:00Z' },
      { ...base, id: '9007199254740993', eventKey: 'k2', entryType: 'PROVISIONAL', occurredAt: '2026-11-25T00:00:00.001Z', recordedAt: '2026-11-25T00:00:00.001Z', reserveDeltaKrw: '-1120', costDeltaKrw: '1120' },
      { ...base, id: '9223372036854775807', eventKey: 'k3', entryType: 'CONFIRM', occurredAt: '2026-11-25T00:00:00.001Z', recordedAt: '2026-11-25T00:00:00.001Z', reserveDeltaKrw: '-280.123457', costDeltaKrw: '280.123457' },
    ]
    mockApi(me, csrf, health, allChannels, on('GET', '/api/v1/budget-reservations/11', 200, { ...reservation, status: 'SETTLED', events }))
    renderApp('/budget/reservations/11')
    const timeline = await screen.findByRole('list', { name: '원장 이벤트' })
    const items = within(timeline).getAllByRole('listitem')
    // 시각 문자열로 정렬하면 '…00.001Z' < '…00Z'라 RESERVE가 뒤로 가고, Number 변환이면 앞의 두 id가 같아진다
    expect(items).toHaveLength(3)
    expect(within(items[0]).getByText('예약')).toBeInTheDocument()
    expect(within(items[1]).getByText('잠정 비용')).toBeInTheDocument()
    expect(within(items[2]).getByText('확정 비용')).toBeInTheDocument()
  })

  it('detects that a lost-response adjustment was already recorded and does not resend it (UI-RV-A01)', async () => {
    const settled = { ...reservation, status: 'SETTLED' }
    let recordedKey: string | undefined
    const calls = mockApi(
      me, csrf, health, allChannels,
      (call) => {
        if (call.method === 'GET' && call.url === '/api/v1/budget-reservations/11') {
          const events = recordedKey
            ? [...settled.events, { ...settled.events[0], id: '2', eventKey: recordedKey, entryType: 'ADJUSTMENT', reserveDeltaKrw: '0', costDeltaKrw: '-50' }]
            : settled.events
          return { status: 200, body: { ...settled, events } }
        }
        if (call.method === 'POST' && call.url.endsWith('/adjustments')) {
          recordedKey = call.headers['Idempotency-Key'] // 서버는 커밋했지만
          return { status: 502, body: error('INTERNAL_SERVER_ERROR', 'gateway') } // 응답이 유실됨
        }
        return undefined
      },
    )
    renderApp('/budget/reservations/11')
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('금액(USD)'), '-0.1')
    await user.type(screen.getByLabelText('메모(필수, 감사 기록)'), 'fix')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('새로고침했거나 화면을 떠났다면')
    await user.click(within(dialog).getByRole('button', { name: '원장에 기록' }))
    expect(await screen.findByText(/원장에 이미 기록된 것을 확인했습니다/)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)
    expect(within(screen.getByRole('list', { name: '원장 이벤트' })).getByText('조정')).toBeInTheDocument()
  })

  it('when a lost response was not recorded, explains that retrying in the same dialog reuses the key (UI-RV-A01)', async () => {
    let attempts = 0
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/budget-reservations/11', 200, { ...reservation, status: 'SETTLED' }),
      (call) => {
        if (call.method === 'POST' && call.url.endsWith('/adjustments')) {
          attempts += 1
          if (attempts === 1) throw new TypeError('Failed to fetch') // 네트워크 끊김
          return { status: 200, body: { ...reservation, status: 'SETTLED' } }
        }
        return undefined
      },
    )
    renderApp('/budget/reservations/11')
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('금액(USD)'), '0.1')
    await user.type(screen.getByLabelText('메모(필수, 감사 기록)'), 'fix')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: '원장에 기록' }))
    expect(await within(dialog).findByText(/현재 원장에서 이 요청의 기록이 확인되지 않았습니다/)).toBeInTheDocument()
    // 자동 재전송 없음
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)
    await user.click(within(dialog).getByRole('button', { name: '원장에 기록' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
  })

  it.each(['ADJUSTMENT', 'REFUND'])('keeps the %s confirmation and original request when ledger verification fails (RVX-03)', async (entryType) => {
    let failed = false
    let recovered = false
    let attempts = 0
    const calls = mockApi(me, csrf, health, allChannels, (call) => {
      if (call.url.endsWith('/adjustments')) {
        if (++attempts === 2) return { status: 200, body: { ...reservation, status: 'SETTLED' } }
        failed = true
        return { status: 502, body: error('INTERNAL_SERVER_ERROR', 'lost') }
      }
      if (call.url.endsWith('/budget-reservations/11')) {
        return failed && !recovered
          ? { status: 503, body: error('SERVICE_UNAVAILABLE', 'verification unavailable') }
          : { status: 200, body: { ...reservation, status: 'SETTLED' } }
      }
    })
    renderApp('/budget/reservations/11')
    const user = userEvent.setup()
    await user.selectOptions(await screen.findByLabelText('유형'), entryType)
    await user.type(await screen.findByLabelText('금액(USD)'), '0.1')
    await user.type(screen.getByLabelText('메모(필수, 감사 기록)'), 'fixture')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: '원장에 기록' }))
    expect(await within(dialog).findByText(/기록 여부도 확인하지 못했습니다/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeDisabled()
    await user.keyboard('{Escape}')
    expect(dialog).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '원장 다시 확인' }))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '원장 다시 확인' })).toBeEnabled())
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)
    recovered = true
    await user.click(within(dialog).getByRole('button', { name: '원장 다시 확인' }))
    expect(await within(dialog).findByText(/실패했다고 단정할 수 없습니다/)).toBeInTheDocument()
    expect(attempts).toBe(1)
    await user.click(within(dialog).getByRole('button', { name: '원장에 기록' }))
    await waitFor(() => expect(dialog).not.toBeInTheDocument())
    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect(posts[1].body).toEqual(posts[0].body)
    expect(posts[1].body).toMatchObject({ entryType, amount: '0.1', note: 'fixture' })
  })

  it('releases a reservation with the confirmed NOT_CHARGED request', async () => {
    const calls = mockApi(me, csrf, health, allChannels,
      on('GET', '/api/v1/budget-reservations/11', 200, reservation),
      on('POST', '/api/v1/budget-reservations/11/resolve', 200, { ...reservation, status: 'RELEASED' }),
    )
    renderApp('/budget/reservations/11')
    const user = userEvent.setup()
    await user.selectOptions(await screen.findByLabelText('결과'), 'NOT_CHARGED')
    await user.type(screen.getByLabelText('판단 근거(필수, 감사 기록)'), 'no invoice')
    await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: '원장에 기록' }))
    await waitFor(() => expect(dialog).not.toBeInTheDocument())
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{ outcome: 'NOT_CHARGED', note: 'no invoice' }])
  })

  it('a reloaded screen uses a new idempotency key, so the guide says to check the ledger first (UI-RV-A01 residual risk)', async () => {
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/budget-reservations/11', 200, { ...reservation, status: 'SETTLED' }),
      on('POST', '/api/v1/budget-reservations/11/adjustments', 500, error('INTERNAL_SERVER_ERROR', 'x')),
    )
    const submitOnce = async () => {
      const user = userEvent.setup()
      await user.type(await screen.findByLabelText('금액(USD)'), '0.1')
      await user.type(screen.getByLabelText('메모(필수, 감사 기록)'), 'fix')
      await user.click(screen.getByRole('button', { name: '기록 전 확인' }))
      await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: '원장에 기록' }))
      await screen.findByText(/현재 원장에서 이 요청의 기록이 확인되지 않았습니다/)
    }
    const first = renderApp('/budget/reservations/11')
    await submitOnce()
    first.unmount()
    renderApp('/budget/reservations/11')
    await submitOnce()
    const keys = calls.filter((c) => c.method === 'POST').map((c) => c.headers['Idempotency-Key'])
    expect(keys).toHaveLength(2)
    expect(keys[1]).not.toBe(keys[0])
  })
})

describe('Ledger, usage, exchange rates', () => {
  it('shows ledger rows with Korean types and signed deltas', async () => {
    mockApi(me, health, allChannels, on('GET', '/api/v1/cost-ledger', 200, page([{
      ...reservation.events[0], id: '2', entryType: 'PROVISIONAL', originalAmount: '0.80', amountKrw: '1120.098765',
      reserveDeltaKrw: '-1120.098765', costDeltaKrw: '1120.098765', costDeltaOriginal: '0.80',
    }])))
    renderApp('/cost-ledger')
    const table = await screen.findByRole('table', { name: '비용 원장' })
    expect(within(table).getByText('잠정 비용')).toBeInTheDocument()
    expect(within(table).getByText('−₩1,120.098765')).toBeInTheDocument()
    expect(within(table).getByText('+₩1,120.098765')).toBeInTheDocument()
    expect(within(table).getByText('0.8 USD')).toBeInTheDocument()
    expect(within(table).getByText('감소', { exact: false })).toBeInTheDocument()
  })

  it('shows channel usage via channel select with the applicable-remaining caveat', async () => {
    const buckets = [
      { id: '1', policyId: '1', scope: 'SYSTEM', channelId: null, periodType: 'DAY', periodStart: '2026-11-24T15:00:00Z', periodEnd: '2026-11-25T15:00:00Z', timezone: 'Asia/Seoul', limitKrw: '30000', reservedKrw: '0', spentKrw: '25000', remainingKrw: '5000' },
      { id: '2', policyId: '3', scope: 'CHANNEL', channelId: '5', periodType: 'DAY', periodStart: '2026-11-24T15:00:00Z', periodEnd: '2026-11-25T15:00:00Z', timezone: 'Asia/Seoul', limitKrw: '10000', reservedKrw: '1000', spentKrw: '1000', remainingKrw: '8000' },
    ]
    const calls = mockApi(me, health, allChannels, on('GET', '/api/v1/budget/usage', 200, { at: '2026-11-25T00:00:00Z', channelId: '5', buckets }))
    renderApp('/budget/usage')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: '채널 예산' }))
    await waitFor(() => expect(screen.getByLabelText('채널')).not.toBeDisabled())
    await user.selectOptions(screen.getByLabelText('채널'), '5')
    expect(await screen.findByRole('heading', { name: '예산 정책 기준 잔여 한도' })).toBeInTheDocument()
    expect(screen.getByText('₩5,000', { selector: 'p span' })).toBeInTheDocument()
    expect(
      screen.getByText(
        '시스템 및 채널 예산 중 가장 낮은 잔여 한도입니다. 실제 지출 가능 여부는 콘텐츠 한도, 지출 제어 상태, 정합성, 채널 상태 및 환율 유효성 등에 따라 결정됩니다.',
        { normalizer: (t) => t.replace(/\s+/g, ' ').trim() },
      ),
    ).toBeInTheDocument()
    expect(calls.some((c) => c.url === '/api/v1/budget/usage?channelId=5')).toBe(true)
  })

  it('registers an exchange rate as a string and previews it as 1 USD = ₩…', async () => {
    const calls = mockApi(
      me, csrf, health,
      on('GET', '/api/v1/exchange-rates', 200, page([])),
      on('POST', '/api/v1/exchange-rates', 201, { id: '6', baseCurrency: 'USD', quoteCurrency: 'KRW', rate: '1500', effectiveFrom: '2026-11-25T00:00:00Z', validUntil: '2026-11-28T00:00:00Z', source: 'MANUAL', note: null, createdBy: '1', createdAt: '2026-11-25T00:00:00Z' }),
    )
    renderApp('/exchange-rates')
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: '환율 등록' }))[0])
    const dialog = await screen.findByRole('dialog', { name: '환율 등록' })
    await user.type(within(dialog).getByLabelText('1 USD 당 원화'), '1,400.1234567891')
    expect(within(dialog).getByText('미리보기: 1 USD = ₩1,400.1234567891')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '환율 등록' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ baseCurrency: 'USD', rate: '1400.1234567891' })
    expect(await screen.findByText('환율 1 USD = ₩1,500을 등록했습니다.')).toBeInTheDocument()
  })

  it('uses the server order for rate status without string sorting or falling back to an older rate (UI-RV-04)', async () => {
    const hour = 3_600_000
    const second = (ms: number) => new Date(Math.floor((Date.now() + ms) / 1000) * 1000).toISOString().replace('.000Z', 'Z')
    const rate = (id: string, baseCurrency: string, effectiveFrom: string, validUntil: string, value: string) => ({
      id, baseCurrency, quoteCurrency: 'KRW', rate: value, effectiveFrom, validUntil, source: 'MANUAL', note: null, createdBy: '1', createdAt: effectiveFrom,
    })
    const t0 = second(-hour) // 정수 초
    const t0Fraction = t0.replace('Z', '.5Z') // 같은 초 + 0.5초: 문자열 비교로는 t0보다 앞선 것으로 보인다
    // 서버 정렬: effective_from DESC, id DESC
    const items = [
      rate('9', 'USD', second(hour), second(73 * hour), '1500'), // 적용 예정
      rate('8', 'USD', t0Fraction, second(70 * hour), '1410'), // 적용 중(최신)
      rate('7', 'USD', t0, second(71 * hour), '1400'), // 이전 환율
      rate('5', 'EUR', second(-2 * hour), second(-hour), '1600'), // 최신이 만료
      rate('4', 'EUR', second(-3 * hour), second(60 * hour), '1550'), // 더 오래된 것이 유효해도 대신 쓰지 않음
    ]
    mockApi(me, csrf, health, on('GET', '/api/v1/exchange-rates', 200, page(items)))
    renderApp('/exchange-rates')
    const table = await screen.findByRole('table')
    const statusOf = (value: string) =>
      within(within(table).getByText(value, { exact: false }).closest('tr')!).getByText(/^(적용 중|적용 예정|만료|이전 환율)$/).textContent
    expect(statusOf('₩1,500')).toBe('적용 예정')
    expect(statusOf('₩1,410')).toBe('적용 중')
    expect(statusOf('₩1,400')).toBe('이전 환율')
    expect(statusOf('₩1,600')).toBe('만료')
    expect(statusOf('₩1,550')).toBe('이전 환율')
  })

  it('shows loading, empty and error states', async () => {
    mockApi(me, health, on('GET', '/api/v1/budget-reservations', 403, error('FORBIDDEN', 'denied')))
    renderApp('/budget/reservations')
    expect(await screen.findByText('불러오는 중…')).toBeInTheDocument()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('권한이 없거나')
    expect(alert).toHaveTextContent('trace-1')
  })
})

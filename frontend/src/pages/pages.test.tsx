import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
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
    await user.click(await screen.findByRole('button', { name: '새 설정 revision' }))
    const dialog = await screen.findByRole('dialog')
    const save = within(dialog).getByRole('button', { name: 'revision 저장' })
    expect(save).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/provider 옵션/), '"x"')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(save)
    expect(await within(dialog).findByText(/JSON 객체 형식이어야 합니다/)).toBeInTheDocument()
    await user.clear(within(dialog).getByLabelText(/provider 옵션/))
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
    expect(await screen.findByText(/가장 작은 잔여/)).toBeInTheDocument()
    expect(screen.getByText('₩5,000', { selector: 'p span' })).toBeInTheDocument()
    expect(screen.getByText(/콘텐츠 유형 한도, 지출 차단/)).toBeInTheDocument()
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

  it('shows loading, empty and error states', async () => {
    mockApi(me, health, on('GET', '/api/v1/budget-reservations', 403, error('FORBIDDEN', 'denied')))
    renderApp('/budget/reservations')
    expect(await screen.findByText('불러오는 중…')).toBeInTheDocument()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('권한이 없거나')
    expect(alert).toHaveTextContent('trace-1')
  })
})

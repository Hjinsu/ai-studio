import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { csrf, error, me, mockApi, on, page, renderApp } from '../test/mockApi'

const channel = {
  id: '5', name: 'science', language: 'en', timezone: 'America/New_York', status: 'ACTIVE', version: 3,
  currentConfigRevisionNo: 2, createdAt: '2026-11-25T00:00:00Z', updatedAt: '2026-11-25T00:00:00Z', archivedAt: null,
}
const config = {
  defaultContentType: 'LONGFORM', shortsMinSeconds: 40, shortsMaxSeconds: 60, longformMinSeconds: 480,
  longformMaxSeconds: 720, contentStrategy: 'Explain', targetAudience: null, narrationStyle: null, visualStyle: null,
  syntheticDisclosureDefault: true, providerOptions: null, channelId: '5', revisionNo: 2, changeNote: null,
  createdBy: '1', createdAt: '2026-11-25T00:00:00Z',
}

describe('Channels', () => {
  it('lists channels in their timezone and creates a channel', async () => {
    const calls = mockApi(
      me, csrf,
      on('GET', '/api/v1/channels', 200, page([channel])),
      on('POST', '/api/v1/channels', 201, { ...channel, id: '9', name: 'new' }),
      on('GET', '/api/v1/channels/9', 200, { ...channel, id: '9', name: 'new' }),
      on('GET', '/api/v1/channels/9/config', 200, { ...config, channelId: '9' }),
      on('GET', '/api/v1/channels/9/config/revisions', 200, page([{ ...config, channelId: '9' }])),
    )
    renderApp('/channels')
    const row = (await screen.findByRole('link', { name: 'science' })).closest('tr')!
    expect(row).toHaveTextContent('(America/New_York)')

    const user = userEvent.setup()
    const form = screen.getByRole('form', { name: '채널 등록' })
    await user.type(within(form).getByLabelText('이름'), 'new')
    await user.clear(within(form).getByLabelText('시간대(IANA)'))
    await user.type(within(form).getByLabelText('시간대(IANA)'), 'Asia/Seoul')
    await user.click(within(form).getByRole('button', { name: '등록' }))

    expect(await screen.findByRole('heading', { name: /new/ })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ name: 'new', language: 'en', timezone: 'Asia/Seoul' })
  })

  it('archives a channel only after confirmation and shows conflicts', async () => {
    const calls = mockApi(
      me, csrf,
      on('GET', '/api/v1/channels/5', 200, channel),
      on('GET', '/api/v1/channels/5/config', 200, config),
      on('GET', '/api/v1/channels/5/config/revisions', 200, page([config])),
      on('POST', '/api/v1/channels/5/archive', 409, error('RESOURCE_CONFLICT', 'The channel was changed.')),
    )
    renderApp('/channels/5')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '보관' }))
    const dialog = screen.getByRole('dialog', { name: '채널을 보관할까요?' })
    expect(dialog).toHaveTextContent('되돌릴 수 없습니다')
    expect(calls.some((c) => c.url.endsWith('/archive'))).toBe(false)

    await user.click(within(dialog).getByRole('button', { name: '보관' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('충돌')
    expect(calls.find((c) => c.url.endsWith('/archive'))?.body).toEqual({ version: 3 })

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('creates a config revision based on the current revision', async () => {
    const calls = mockApi(
      me, csrf,
      on('GET', '/api/v1/channels/5', 200, channel),
      on('GET', '/api/v1/channels/5/config', 200, config),
      on('GET', '/api/v1/channels/5/config/revisions', 200, page([config])),
      on('POST', '/api/v1/channels/5/config/revisions', 201, { ...config, revisionNo: 3 }),
    )
    renderApp('/channels/5')
    const user = userEvent.setup()
    await user.click(await screen.findByText(/새 설정 revision 만들기/))
    await user.type(screen.getByLabelText('provider 옵션(JSON, 비밀값 금지)'), '"x"')
    await user.click(screen.getByRole('button', { name: 'revision 생성' }))
    expect(await screen.findByText('provider 옵션은 JSON 객체여야 합니다.')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('provider 옵션(JSON, 비밀값 금지)'))
    await user.type(screen.getByLabelText('변경 메모'), 'tone')
    await user.click(screen.getByRole('button', { name: 'revision 생성' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      baseRevisionNo: 2,
      changeNote: 'tone',
      settings: {
        defaultContentType: 'LONGFORM', shortsMinSeconds: 40, shortsMaxSeconds: 60, longformMinSeconds: 480,
        longformMaxSeconds: 720, contentStrategy: 'Explain', targetAudience: null, narrationStyle: null,
        visualStyle: null, syntheticDisclosureDefault: true, providerOptions: null,
      },
    })
  })
})

describe('Spending control', () => {
  it('requires a reason and sends the control version', async () => {
    const calls = mockApi(
      me, csrf,
      on('GET', '/api/v1/budget/control', 200, {
        spendingEnabled: true, spendingChangedAt: null, spendingChangedBy: null, spendingChangeReason: null,
        consistency: { status: 'OK', checkedAt: null, issueCount: 0 }, version: 4, updatedAt: '2026-11-25T00:00:00Z',
      }),
      on('POST', '/api/v1/budget/control/spending', 200, {
        spendingEnabled: false, spendingChangedAt: '2026-11-25T00:00:00Z', spendingChangedBy: '1',
        spendingChangeReason: 'incident', consistency: { status: 'OK', checkedAt: null, issueCount: 0 }, version: 5,
        updatedAt: '2026-11-25T00:00:00Z',
      }),
    )
    renderApp('/budget/control')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '긴급 지출 차단' }))
    const dialog = screen.getByRole('dialog')
    const confirm = within(dialog).getByRole('button', { name: '차단' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText('사유(필수, 감사 기록)'), 'incident')
    await user.click(confirm)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ enabled: false, reason: 'incident', version: 4 })
  })

  it('shows consistency issues', async () => {
    mockApi(
      me, csrf,
      on('GET', '/api/v1/budget/control', 200, {
        spendingEnabled: false, consistency: { status: 'OK', checkedAt: null, issueCount: 0 }, version: 0,
        updatedAt: '2026-11-25T00:00:00Z',
      }),
      on('POST', '/api/v1/budget/consistency-checks', 200, {
        checkedAt: '2026-11-25T00:00:00Z', consistent: false,
        issues: [{ checkCode: 'BUCKET_SPENT_MISMATCH', entityType: 'BUCKET', entityId: '3', expected: '1', actual: '2' }],
      }),
    )
    renderApp('/budget/control')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '지금 검사' }))
    expect(await screen.findByText('BUCKET_SPENT_MISMATCH')).toBeInTheDocument()
    expect(screen.getByText(/이슈 1건/)).toBeInTheDocument()
  })
})

const reservation = {
  id: '11', requestKey: 'req-key-1', channelId: null, purpose: 'image', originalCurrency: 'USD',
  estimateOriginal: '1.00', exchangeRateId: '2', fxRate: '1400.1234567891', estimateKrw: '1400.123457',
  status: 'RECONCILIATION_REQUIRED', reservedRemainingKrw: '1400.123457', recognizedCostKrw: '0',
  recognizedCostOriginal: '0', reconciliationReason: 'timeout', createdAt: '2026-11-25T00:00:00Z',
  updatedAt: '2026-11-25T00:00:00Z', closedAt: null, bucketIds: ['1', '2'], events: [],
}

describe('Reservation settlement', () => {
  it('confirms a charge with one idempotency key reused on retry', async () => {
    let attempts = 0
    const calls = mockApi(
      me, csrf,
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
    expect(await screen.findByText('1400.1234567891 (환율 #2)')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('확정 총액(USD)'), '1.20')
    await user.type(screen.getByLabelText('판단 근거(필수, 감사 기록)'), 'invoice')
    await user.click(screen.getByRole('button', { name: '확인' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('1.20 USD')
    await user.click(within(dialog).getByRole('button', { name: '기록' }))
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '기록' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[0].headers['Idempotency-Key']).toMatch(/^ui-[0-9a-f-]{36}$/)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect(posts[0].body).toEqual({ outcome: 'CHARGED', note: 'invoice', finalAmountOriginal: '1.20' })
  })

  it('late charge on a released reservation is CHARGED only and requires an external reference', async () => {
    const calls = mockApi(
      me, csrf,
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
    await user.click(screen.getByRole('button', { name: '확인' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '기록' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      outcome: 'CHARGED', note: 'late invoice', finalAmountOriginal: '0.5', externalRef: 'inv-9',
    })
  })

  it('adjusts a settled reservation in KRW basis', async () => {
    const calls = mockApi(
      me, csrf,
      on('GET', '/api/v1/budget-reservations/11', 200, { ...reservation, status: 'SETTLED' }),
      on('POST', '/api/v1/budget-reservations/11/adjustments', 200, { ...reservation, status: 'SETTLED' }),
    )
    renderApp('/budget/reservations/11')
    const user = userEvent.setup()
    await user.selectOptions(await screen.findByLabelText('기준 통화'), 'KRW')
    await user.type(screen.getByLabelText('금액(KRW)'), '-50')
    await user.clear(screen.getByLabelText('사유 코드'))
    await user.type(screen.getByLabelText('사유 코드'), 'FX_DIFFERENCE')
    await user.type(screen.getByLabelText('메모(필수, 감사 기록)'), 'card rate')
    await user.click(screen.getByRole('button', { name: '기록' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '기록' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    const post = calls.find((c) => c.method === 'POST')!
    expect(post.body).toEqual({
      entryType: 'ADJUSTMENT', basis: 'KRW', amount: '-50', reasonCode: 'FX_DIFFERENCE', note: 'card rate',
    })
    expect(post.headers['Idempotency-Key']).toBeDefined()
  })
})

describe('Budget policies and exchange rates', () => {
  it('creates a CHANNEL policy with a string limit', async () => {
    const calls = mockApi(
      me, csrf,
      on('GET', '/api/v1/budget-policies', 200, page([])),
      on('POST', '/api/v1/budget-policies', 201, {}),
    )
    renderApp('/budget/policies')
    const user = userEvent.setup()
    const form = await screen.findByRole('form', { name: '정책 생성' })
    await user.selectOptions(within(form).getByLabelText('범위'), 'CHANNEL')
    await user.type(within(form).getByLabelText('채널 ID'), '5')
    await user.type(within(form).getByLabelText('한도(KRW)'), '30000.000001')
    await user.click(within(form).getByRole('button', { name: '생성' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      scope: 'CHANNEL', channelId: '5', periodType: 'DAY', limitKrw: '30000.000001', timezone: 'Asia/Seoul',
    })
  })

  it('registers an exchange rate as a string', async () => {
    const calls = mockApi(
      me, csrf,
      on('GET', '/api/v1/exchange-rates', 200, page([])),
      on('POST', '/api/v1/exchange-rates', 201, {}),
    )
    renderApp('/exchange-rates')
    const user = userEvent.setup()
    const form = await screen.findByRole('form', { name: '환율 등록' })
    await user.type(within(form).getByLabelText('환율(KRW)'), '1400.1234567891')
    await user.click(within(form).getByRole('button', { name: '등록' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ baseCurrency: 'USD', rate: '1400.1234567891' })
  })

  it('shows ledger rows with separate reserve and cost deltas', async () => {
    mockApi(
      me,
      on('GET', '/api/v1/cost-ledger', 200, page([{
        id: '1', eventKey: 'e1', reservationId: '11', requestKey: 'r', entryType: 'PROVISIONAL', reasonCode: null,
        originalCurrency: 'USD', originalAmount: '0.80', fxRate: '1400.1234567891', exchangeRateId: '2',
        amountKrw: '1120.098765', reserveDeltaKrw: '-1120.098765', costDeltaKrw: '1120.098765',
        costDeltaOriginal: '0.80', priceVersion: null, externalRef: null, actorType: 'SYSTEM', actorUserId: null,
        occurredAt: '2026-11-25T00:00:00Z', recordedAt: '2026-11-25T00:00:00Z',
      }])),
    )
    renderApp('/cost-ledger')
    expect(await screen.findByText('-1120.098765')).toBeInTheDocument()
    expect(screen.getByText('1120.098765')).toBeInTheDocument()
    expect(screen.getByText('0.80 USD')).toBeInTheDocument()
  })
})

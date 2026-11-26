import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { channels, csrf, error, health, me, mockApi, on, page, renderApp } from '../test/mockApi'

const allChannels = on('GET', '/api/v1/channels', 200, { ...page(channels), size: 100 })
const control = on('GET', '/api/v1/budget/control', 200, {
  spendingEnabled: true, version: 2, spendingChangedAt: null, spendingChangedBy: null, spendingChangeReason: null,
  consistency: { status: 'OK', checkedAt: null, issueCount: 0 },
})

const stages = ['DISCOVER', 'SELECT', 'RESEARCH', 'SCRIPT', 'SCENES', 'IMAGE', 'TTS', 'SUBTITLE', 'RENDER', 'METADATA', 'QC']

function detail(
  status: string,
  stepStatus: (stage: string) => string,
  extra: Record<string, unknown> = {},
  stepError: (stage: string) => string | null = () => null,
) {
  return {
    id: '42', channelId: '5', contentType: 'SHORTS', status, contentId: null, runId: null, cancelRequestedAt: null,
    limitKrw: '2000.000000', reservedKrw: '0', recognizedKrw: '1.000000',
    steps: stages.map((stage) => ({
      stage, targetKey: 'video', inputGeneration: 1, status: stepStatus(stage), attempts: stage === 'DISCOVER' ? 1 : 0,
      lastErrorCode: stepError(stage),
    })),
    ...extra,
  }
}

const TOKEN_1 = 'a'.repeat(64)
const TOKEN_2 = 'b'.repeat(64)

const scores = { interest: 25, channelFit: 20, originality: 20, visualPotential: 11, evidencePotential: 15, costEfficiency: 5 }

function candidate(id: number, title: string, blockCodes: string[] = [], total = 96) {
  return {
    candidateId: id,
    candidate: {
      coreTopic: `Topic ${id}`, centralQuestion: `Why does ${title.toLowerCase()} happen?`, title,
      angle: 'Walk through the mechanism step by step.', outline: ['Hook diagram', 'Mechanism animation'],
      evidencePlan: [{ sourceType: 'SPACE_AGENCY', searchQuestion: 'What do current sources say?' }],
      assertions: blockCodes.includes('UNSUPPORTED_ASSERTION') ? [{ assertion: 'An unverified claim', evidenceId: null }] : [],
      suggestedScores: scores, rationale: 'FAKE',
    },
    scores, totalScore: total, blockCodes,
    comparisons: blockCodes.includes('DUPLICATE') ? [{ topicId: '7', contentId: '8', revisionId: '9', reason: 'EXACT_TITLE' }] : [],
    rationale: `rules v1: total ${total}/100`,
  }
}

const waiting = {
  selectionMode: 'MANUAL', candidateCount: 5, version: 1, batchToken: TOKEN_1, awaitingSelection: true, chosenCandidateId: null,
  selectedCandidateId: null, selectionReason: null,
  candidates: [
    candidate(1, 'Why the Sky Is Blue', ['DUPLICATE'], 98),
    candidate(2, 'A Teaspoon of Neutron Star', [], 96),
    candidate(3, 'Venus Day Outlasts Its Year', [], 90),
    candidate(4, 'Tardigrade Survival', ['UNSUPPORTED_ASSERTION'], 88),
    candidate(5, 'Spider Silk Versus Steel', ['CHANNEL_PROHIBITED'], 70),
  ],
}

describe('Production start', () => {
  it('requires a channel and starts SHORTS discovery only after confirmation with the default options', async () => {
    const calls = mockApi(
      me, csrf, health, allChannels, control,
      on('POST', '/api/v1/production-requests', 201, { id: '42', status: 'QUEUED' }),
      on('GET', '/api/v1/production-requests/42', 200, detail('QUEUED', () => 'PENDING')),
      on('GET', '/api/v1/production-requests/42/discovery', 200, { ...waiting, selectionMode: 'AUTO', awaitingSelection: false, version: 0, candidateCount: 8, candidates: [] }),
    )
    const { router } = renderApp('/production')
    const user = userEvent.setup()
    expect(await screen.findByRole('heading', { name: '제작 시작' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /자동 선정/ })).toBeChecked()
    expect(screen.getByLabelText('후보 수')).toHaveValue('8')

    await user.click(screen.getByRole('button', { name: '제작 시작…' }))
    expect(await screen.findByText('채널을 선택하세요.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)

    await waitFor(() => expect(within(screen.getByLabelText('채널')).getByRole('option', { name: 'science' })).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('채널'), '5')
    await user.click(screen.getByRole('button', { name: '제작 시작…' }))
    const dialog = await screen.findByRole('alertdialog', { name: '제작을 시작할까요?' })
    expect(dialog).toHaveTextContent('science')
    expect(dialog).toHaveTextContent('자동 선정 · 후보 8개')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: '제작 시작' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/production/42'))
    const post = calls.find((c) => c.method === 'POST' && c.url === '/api/v1/production-requests')
    expect(post?.body).toEqual({ channelId: '5', contentType: 'SHORTS', selectionMode: 'AUTO', candidateCount: 8 })
    expect(post?.headers['Idempotency-Key']).toMatch(/^ui-/)
    expect(await screen.findByText('소재 후보를 발굴하는 중입니다')).toBeInTheDocument()
  })

  it('sends manual mode and count, omits discovery options for long-form, and keeps the key on retry', async () => {
    let attempts = 0
    const calls = mockApi(me, csrf, health, allChannels, control, (call) => {
      if (call.method === 'POST' && call.url === '/api/v1/production-requests') {
        attempts++
        return attempts === 1 ? { status: 503, body: error('SERVICE_UNAVAILABLE', 'busy') } : { status: 201, body: { id: '43', status: 'QUEUED' } }
      }
    }, on('GET', '/api/v1/production-requests/43', 200, detail('QUEUED', () => 'PENDING', { id: '43', contentType: 'LONGFORM' })),
    on('GET', '/api/v1/production-requests/43/discovery', 404, error('RESOURCE_NOT_FOUND', 'none')))
    renderApp('/production')
    const user = userEvent.setup()
    await waitFor(() => expect(within(screen.getByLabelText('채널')).getByRole('option', { name: 'science' })).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('채널'), '5')
    await user.click(screen.getByRole('radio', { name: /직접 선택/ }))
    await user.selectOptions(screen.getByLabelText('후보 수'), '10')
    await user.selectOptions(screen.getByLabelText('콘텐츠 유형'), 'LONGFORM')
    expect(screen.queryByLabelText('후보 수')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '제작 시작…' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: '제작 시작' }))
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '제작 시작' }))
    expect(await screen.findByText('소재 발굴을 사용하지 않는 요청입니다')).toBeInTheDocument()
    const posts = calls.filter((c) => c.method === 'POST' && c.url === '/api/v1/production-requests')
    expect(posts).toHaveLength(2)
    expect(posts[0].body).toEqual({ channelId: '5', contentType: 'LONGFORM' })
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
  })

  it('warns that discovery will not run while spending is blocked', async () => {
    mockApi(me, csrf, health, allChannels, on('GET', '/api/v1/budget/control', 200, {
      spendingEnabled: false, version: 1, spendingChangedAt: null, spendingChangedBy: null, spendingChangeReason: null,
      consistency: { status: 'OK', checkedAt: null, issueCount: 0 },
    }))
    renderApp('/production')
    expect(await screen.findByText(/신규 지출이 차단되어 있습니다/)).toBeInTheDocument()
  })
})

describe('Production request detail', () => {
  it('shows evaluated candidates with scores and block reasons and records a manual choice after confirmation', async () => {
    let chosen = false
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/production-requests/42', 200, detail('RUNNING', (s) => (s === 'DISCOVER' ? 'SUCCEEDED' : 'PENDING'))),
      (call) => call.method === 'GET' && call.url === '/api/v1/production-requests/42/discovery'
        ? { status: 200, body: chosen ? { ...waiting, version: 2, batchToken: TOKEN_2, awaitingSelection: false, chosenCandidateId: 3 } : waiting }
        : undefined,
      (call) => {
        if (call.method === 'POST' && call.url === '/api/v1/production-requests/42/selection') {
          chosen = true
          return { status: 200, body: { ...waiting, version: 2, batchToken: TOKEN_2, awaitingSelection: false, chosenCandidateId: 3 } }
        }
      },
    )
    renderApp('/production/42')
    const user = userEvent.setup()
    expect(await screen.findByRole('heading', { name: '제작 요청 #42' })).toBeInTheDocument()
    expect(screen.getByText('진행 중')).toBeInTheDocument()
    const list = await screen.findByRole('list', { name: '소재 후보 목록' })
    expect(screen.getByText(/직접 선택 · 후보 5개 · 적격 2개/)).toBeInTheDocument()
    expect(screen.getByText(/적격 후보 중 하나를 선택하면/)).toBeInTheDocument()
    const items = within(list).getAllByRole('listitem')
    expect(within(items[0]).getByRole('heading')).toHaveTextContent('A Teaspoon of Neutron Star')
    expect(within(list).getByText('중복')).toBeInTheDocument()
    expect(within(list).getByText('근거 없는 주장')).toBeInTheDocument()
    expect(within(list).getByText('금지 소재')).toBeInTheDocument()
    expect(within(list).getAllByText('흥미도')).toHaveLength(5)
    expect(within(list).getByLabelText('총점 96점(100점 만점)')).toBeInTheDocument()
    expect(within(list).getAllByRole('button', { name: '이 소재 선택' })).toHaveLength(2)
    expect(within(list).getByText(/소재 #7 · 콘텐츠 #8 · revision #9 — EXACT_TITLE/)).toBeInTheDocument()

    const venus = items.find((item) => item.textContent?.includes('Venus Day Outlasts Its Year'))!
    await user.click(within(venus).getByRole('button', { name: '이 소재 선택' }))
    const dialog = await screen.findByRole('alertdialog', { name: '이 소재로 진행할까요?' })
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: '이 소재 선택' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ candidateId: 3, version: 1, batchToken: TOKEN_1 })
    expect(await screen.findByText(/후보 3번 선택을 기록했습니다/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이 소재 선택' })).not.toBeInTheDocument()
  })

  it('keeps the dialog open with the conflict when the batch changed meanwhile', async () => {
    mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/production-requests/42', 200, detail('RUNNING', (s) => (s === 'DISCOVER' ? 'SUCCEEDED' : 'PENDING'))),
      on('GET', '/api/v1/production-requests/42/discovery', 200, waiting),
      on('POST', '/api/v1/production-requests/42/selection', 409, error('RESOURCE_CONFLICT', 'stale version')),
    )
    renderApp('/production/42')
    const user = userEvent.setup()
    const list = await screen.findByRole('list', { name: '소재 후보 목록' })
    await user.click(within(list).getAllByRole('button', { name: '이 소재 선택' })[0])
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: '이 소재 선택' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('충돌')
  })

  it('asks for a new choice when polling brings a different batch while the dialog is open', async () => {
    let dialogOpen = false
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/production-requests/42', 200, detail('RUNNING', (s) => (s === 'DISCOVER' ? 'SUCCEEDED' : 'PENDING'))),
      (call) => call.method === 'GET' && call.url === '/api/v1/production-requests/42/discovery'
        ? { status: 200, body: dialogOpen ? { ...waiting, version: 2, batchToken: TOKEN_2 } : waiting }
        : undefined,
    )
    renderApp('/production/42')
    const user = userEvent.setup()
    const list = await screen.findByRole('list', { name: '소재 후보 목록' })
    await user.click(within(list).getAllByRole('button', { name: '이 소재 선택' })[0])
    const dialog = await screen.findByRole('alertdialog')
    dialogOpen = true
    expect(await within(dialog).findByText(/후보 평가가 바뀌었습니다/, undefined, { timeout: 8000 })).toBeInTheDocument()
    const confirm = within(dialog).getByRole('button', { name: '이 소재 선택' })
    expect(confirm).toBeDisabled()
    await user.click(confirm)
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: '취소' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
  }, 15_000)

  it('shows the automatic selection with its reason and the final state without choice buttons', async () => {
    mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/production-requests/42', 200, detail('SUCCEEDED', () => 'SUCCEEDED', { contentId: '8', runId: '9', recognizedKrw: '8.000000' })),
      on('GET', '/api/v1/production-requests/42/discovery', 200, {
        ...waiting, selectionMode: 'AUTO', awaitingSelection: false, version: 2, selectedCandidateId: 2,
        selectionReason: 'AUTO: highest ranked of 2 eligible candidates after the SELECT re-check',
      }),
    )
    renderApp('/production/42')
    expect(await screen.findByText('선정 소재: A Teaspoon of Neutron Star')).toBeInTheDocument()
    expect(screen.getByText(/AUTO: highest ranked of 2 eligible/)).toBeInTheDocument()
    expect(screen.getByText('선정됨')).toBeInTheDocument()
    expect(screen.getByText('FAKE 완료')).toBeInTheDocument()
    expect(screen.getByText(/실제 AI 호출과 영상 파일 생성 없이/)).toBeInTheDocument()
    expect(screen.getByText('₩8')).toBeInTheDocument()
    expect(screen.getByText('₩2,000')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이 소재 선택' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '제작 취소…' })).not.toBeInTheDocument()
    const steps = screen.getByRole('table', { name: '진행 단계' })
    expect(within(steps).getByText('소재 발굴')).toBeInTheDocument()
    expect(within(steps).getByText('품질 검수')).toBeInTheDocument()
  })

  it('reports when every candidate was blocked', async () => {
    mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/production-requests/42', 200, detail('FAILED', (s) => (s === 'DISCOVER' ? 'FAILED' : 'PENDING'), {},
        (s) => (s === 'DISCOVER' ? 'DISCOVERY_NO_ELIGIBLE_CANDIDATE' : null))),
      on('GET', '/api/v1/production-requests/42/discovery', 200, {
        ...waiting, selectionMode: 'AUTO', awaitingSelection: false,
        candidates: waiting.candidates.map((c) => ({ ...c, blockCodes: ['DUPLICATE'] })),
      }),
    )
    renderApp('/production/42')
    expect(await screen.findByText(/모든 후보가 차단되었습니다/)).toBeInTheDocument()
    expect(screen.getAllByText('실패').length).toBeGreaterThan(0)
    const steps = screen.getByRole('table', { name: '진행 단계' })
    expect(within(steps).getByText('선정 가능한 후보 없음')).toBeInTheDocument()
    expect(within(steps).getByText('DISCOVERY_NO_ELIGIBLE_CANDIDATE')).toBeInTheDocument()
  })

  it('labels a malformed discovery response as an executed failure with a confirmed cost', async () => {
    mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/production-requests/42', 200, detail('FAILED', (s) => (s === 'DISCOVER' ? 'FAILED' : 'PENDING'), {},
        (s) => (s === 'DISCOVER' ? 'EFFECT_EXECUTED_FAILED' : null))),
      on('GET', '/api/v1/production-requests/42/discovery', 200, { ...waiting, selectionMode: 'AUTO', awaitingSelection: false, candidates: [] }),
    )
    renderApp('/production/42')
    expect(await screen.findByText('소재 후보를 만들지 못했습니다')).toBeInTheDocument()
    const steps = screen.getByRole('table', { name: '진행 단계' })
    expect(within(steps).getByText(/외부 실행 실패\(비용 확정\)/)).toBeInTheDocument()
    expect(screen.getByText('₩1')).toBeInTheDocument()
  })

  it('cancels only after confirmation', async () => {
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/production-requests/42', 200, detail('RUNNING', (s) => (s === 'DISCOVER' ? 'SUCCEEDED' : 'PENDING'))),
      on('GET', '/api/v1/production-requests/42/discovery', 200, waiting),
      on('POST', '/api/v1/production-requests/42/cancel', 200, detail('RUNNING', () => 'PENDING', { cancelRequestedAt: '2026-11-26T00:00:00Z' })),
    )
    renderApp('/production/42')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '제작 취소…' }))
    const dialog = await screen.findByRole('alertdialog', { name: '제작을 취소할까요?' })
    expect(calls.some((c) => c.url.endsWith('/cancel'))).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: '제작 취소' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/cancel'))).toBe(true))
  })

  it('shows permission errors from the request query', async () => {
    mockApi(me, csrf, health, allChannels, on('GET', '/api/v1/production-requests/42', 403, error('FORBIDDEN', 'denied')))
    renderApp('/production/42')
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('treats an unknown request as not found', async () => {
    mockApi(me, csrf, health, allChannels, on('GET', '/api/v1/production-requests/404', 404, error('RESOURCE_NOT_FOUND', 'missing')))
    renderApp('/production/404')
    expect(await screen.findByRole('alert')).toHaveTextContent(/찾을 수 없|없습니다|missing/)
  })
})

describe('Production request history', () => {
  const items = [
    {
      id: '43', channelId: '5', channelName: 'science', selectedTitle: null, status: 'RUNNING',
      limitKrw: '2000.000000', reservedKrw: '1.000000', recognizedKrw: '0', createdAt: '2026-11-26T01:00:00Z',
    },
    {
      id: '42', channelId: '5', channelName: 'science', selectedTitle: 'A Teaspoon of Neutron Star', status: 'SUCCEEDED',
      limitKrw: '2000.000000', reservedKrw: '0', recognizedKrw: '8.000000', createdAt: '2026-11-26T00:00:00Z',
    },
  ]

  it('lists requests newest first with channel, selected topic, FAKE status, cost and a link to the detail', async () => {
    const calls = mockApi(
      me, csrf, health, allChannels,
      on('GET', '/api/v1/production-requests', 200, { ...page(items), totalElements: 2 }),
      on('GET', '/api/v1/production-requests/42', 200, detail('SUCCEEDED', () => 'SUCCEEDED', { recognizedKrw: '8.000000' })),
      on('GET', '/api/v1/production-requests/42/discovery', 404, error('RESOURCE_NOT_FOUND', 'none')),
    )
    const { router } = renderApp('/production/requests')
    const user = userEvent.setup()
    expect(await screen.findByRole('heading', { name: '제작 이력' })).toBeInTheDocument()
    expect(screen.getByText(/실제 AI 호출과 영상 파일 생성 없이/)).toBeInTheDocument()
    const nav = screen.getAllByRole('navigation', { name: '주 메뉴' })[0]
    expect(within(nav).getByRole('link', { name: '제작 이력' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: '제작 시작' })).not.toHaveAttribute('aria-current')
    const table = await screen.findByRole('table', { name: '제작 요청 목록' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('#43')
    expect(rows[0]).toHaveTextContent('미선정')
    expect(rows[0]).toHaveTextContent('진행 중')
    expect(rows[1]).toHaveTextContent('A Teaspoon of Neutron Star')
    expect(rows[1]).toHaveTextContent('FAKE 완료')
    expect(rows[1]).toHaveTextContent('₩8')
    expect(rows[1]).toHaveTextContent('science')
    expect(calls.find((c) => c.url.startsWith('/api/v1/production-requests?'))?.url).toBe('/api/v1/production-requests?page=0&size=20')
    await user.click(within(rows[1]).getByRole('link', { name: '#42' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/production/42'))
    expect(await screen.findByRole('heading', { name: '제작 요청 #42' })).toBeInTheDocument()
  })

  it('shows an empty state when there are no requests', async () => {
    mockApi(me, csrf, health, allChannels, on('GET', '/api/v1/production-requests', 200, page([])))
    renderApp('/production/requests')
    expect(await screen.findByText('제작 요청이 없습니다')).toBeInTheDocument()
  })

  it('shows a permission error from the list query', async () => {
    mockApi(me, csrf, health, allChannels, on('GET', '/api/v1/production-requests', 403, error('FORBIDDEN', 'denied')))
    renderApp('/production/requests')
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})

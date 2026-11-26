import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { channels, csrf, error, health, me, mockApi, on, page, renderApp } from '../test/mockApi'
import { productionKey } from '../api/production'

const allChannels = on('GET', '/api/v1/channels', 200, { ...page(channels), size: 100 })
const stages = ['DISCOVER', 'SELECT', 'RESEARCH', 'SCRIPT', 'SCENES', 'IMAGE', 'TTS', 'SUBTITLE', 'RENDER', 'METADATA', 'QC']

function detail(status: string, stepStatus: (stage: string) => string, stepError: (stage: string) => string | null = () => null) {
  return {
    id: '42', channelId: '5', contentType: 'SHORTS', status, contentId: '8', runId: '9', cancelRequestedAt: null,
    limitKrw: '2000.000000', reservedKrw: '0', recognizedKrw: '5.000000',
    steps: stages.map((stage) => ({
      stage, targetKey: 'video', inputGeneration: 1, status: stepStatus(stage), attempts: 1, lastErrorCode: stepError(stage),
    })),
  }
}

const selected = {
  selectionMode: 'AUTO', candidateCount: 5, version: 1, batchToken: 'a'.repeat(64), awaitingSelection: false,
  chosenCandidateId: null, selectedCandidateId: null, selectionReason: null, candidates: [],
}

const fixtureSource = {
  id: 'neutron-star:d1', origin: 'FIXTURE', status: 'FIXTURE', url: 'https://fixtures.ai-studio.invalid/neutron-star/basics',
  title: 'Neutron star basics (offline fixture)', publisher: 'AI Studio offline fixture, not a real publication',
  checkedAtUtc: null, documentHash: 'c'.repeat(64),
}

const research = {
  question: 'How heavy would a teaspoon of neutron star be?',
  angle: 'Compare the effect against familiar everyday scales.',
  investigations: [{ id: 'i1', question: 'What do current sources say?', requestedEvidence: 'TEXTBOOK', status: 'ANSWERED_FROM_FIXTURE' }],
  claims: [
    {
      id: 'c2', kind: 'FACT', text: 'A typical neutron star holds about 1.4 times the mass of the Sun.',
      evidenceRefs: [{ sourceId: 'neutron-star:d1', locator: 'paragraph 1', supportingExcerpt: 'about 1.4 times the mass of the Sun', relation: 'SUPPORTS' }],
      uncertainty: null,
    },
    {
      id: 'c5', kind: 'HYPOTHESIS', text: 'The innermost core may contain free quarks.',
      evidenceRefs: [], uncertainty: 'Model-based idea; the core is still unknown.',
    },
    {
      id: 'c4', kind: 'SCENARIO', text: 'If a sugar cube were brought to Earth, it would decompress.',
      evidenceRefs: [], uncertainty: 'Thought experiment.',
    },
    {
      id: 'c6', kind: 'FACT', text: 'Neutron stars are the densest objects in the universe.',
      evidenceRefs: [{ sourceId: 'neutron-star:d1', locator: 'paragraph 1', supportingExcerpt: 'one of the densest forms', relation: 'INSUFFICIENT' }],
      uncertainty: null,
    },
  ],
  sources: [fixtureSource],
  limitations: ['The state of the core is a hypothesis.'],
  scriptClaimIds: ['c2', 'c4', 'c5'],
}

const script = {
  language: 'en', hookSegmentIds: ['s1'], estimatedDurationMs: 57390, timingRuleVersion: 'speech-estimate-1',
  editorialIntent: 'Let viewers guess the weight, then flip it.',
  segments: [
    { id: 's1', narration: 'Guess what this sugar cube weighs?', claimIds: [] },
    { id: 's2', narration: 'Now squeeze about 1.4 times the mass of our Sun into a small ball.', claimIds: ['c2'] },
  ],
}

const scenes = {
  visualStyle: 'Clean infographic; vertical frame',
  scenes: [
    {
      id: 'scene-1', order: 1, segmentIds: ['s1'], estimatedDurationMs: 2630, narration: 'Guess what this sugar cube weighs?',
      visualDescription: 'A glowing sugar cube on a kitchen scale.', imagePrompt: 'Close-up of a glowing sugar cube, vertical',
      direction: 'Slow push-in.', transition: 'Hard cut', subtitleText: 'Guess what this sugar cube weighs?',
    },
    {
      id: 'scene-2', order: 2, segmentIds: ['s2'], estimatedDurationMs: 54760,
      narration: 'Now squeeze about 1.4 times the mass of our Sun into a small ball.',
      visualDescription: 'A glowing sphere shrinking down.', imagePrompt: 'Animated compression of a glowing sphere, vertical',
      direction: 'Animated compression shot.', transition: null,
      subtitleText: 'Now squeeze about 1.4 times the mass of our Sun into a small ball.',
    },
  ],
}

const recommendation = { severity: 'RECOMMENDATION', code: 'OPTIONAL_CLAIM_EXCLUDED', location: 'research.claims[c6]', references: ['neutron-star:d1'], correction: 'Not confirmed; it stays out of the script.' }
const hash = 'd'.repeat(64)

function planning(results: unknown[]) {
  return { executionMode: 'FAKE', results }
}

const done = planning([
  { operationId: '11', stage: 'RESEARCH', bodyHash: hash, body: research, validation: { findings: [recommendation] } },
  { operationId: '12', stage: 'SCRIPT', bodyHash: hash, body: script, validation: { findings: [] } },
  { operationId: '13', stage: 'SCENES', bodyHash: hash, body: scenes, validation: { findings: [] } },
])

function api(detailBody: unknown, planningHandler: ReturnType<typeof on>) {
  return mockApi(
    me, csrf, health, allChannels,
    on('GET', '/api/v1/production-requests/42', 200, detailBody),
    on('GET', '/api/v1/production-requests/42/discovery', 200, selected),
    planningHandler,
  )
}

describe('Production planning results (V2-03)', () => {
  it('separates facts, hypotheses and scenarios, marks FIXTURE sources and shows script and scenes read-only', async () => {
    api(detail('SUCCEEDED', () => 'SUCCEEDED'), on('GET', '/api/v1/production-requests/42/planning', 200, done))
    renderApp('/production/42')
    const section = await screen.findByRole('region', { name: '자료 조사·대본·장면' })
    expect(within(section).getByText('FAKE 실행 결과입니다. 실제 제작 결과가 아닙니다.')).toBeInTheDocument()
    expect(within(section).getByText(/실제 문서 조회나 사실 확인을 거치지 않았습니다/)).toBeInTheDocument()

    const facts = within(section).getByRole('list', { name: '사실 주장' })
    expect(within(facts).getAllByRole('listitem').filter((item) => item.textContent?.includes('c2'))[0]).toHaveTextContent('대본 사용')
    expect(within(facts).getByText(/densest objects/).closest('li')).toHaveTextContent('대본 제외')
    expect(within(within(facts).getByRole('list', { name: 'c2 근거' })).getByText('지지')).toBeInTheDocument()
    expect(within(within(facts).getByRole('list', { name: 'c6 근거' })).getByText('근거 부족')).toBeInTheDocument()
    expect(within(section).getByRole('list', { name: '가설 주장' })).toHaveTextContent('한계: Model-based idea')
    expect(within(section).getByRole('list', { name: '가상 시나리오 주장' })).toHaveTextContent('Thought experiment.')

    const sources = within(section).getByRole('list', { name: '출처' })
    expect(within(sources).getByText('FIXTURE 시험 자료')).toBeInTheDocument()
    expect(within(sources).queryByText('조회 확인')).not.toBeInTheDocument()
    expect(within(sources).queryByRole('link')).not.toBeInTheDocument()
    expect(within(sources).getByText(/조회하지 않음/)).toBeInTheDocument()

    const segments = within(section).getByRole('list', { name: '대본 구간' })
    expect(within(segments).getAllByRole('listitem')[0]).toHaveTextContent('Hook')
    expect(within(segments).getAllByRole('listitem')[1]).toHaveTextContent('근거 주장 c2')
    expect(within(section).getByText(/예상 발화 57\.4초 · 목표 40~60초/)).toBeInTheDocument()

    const sceneList = within(section).getByRole('list', { name: '장면 목록' })
    const second = within(sceneList).getAllByRole('listitem')[1]
    expect(second).toHaveTextContent('장면 2')
    expect(second).toHaveTextContent('2.6초–57.4초')
    expect(second).toHaveTextContent('자막 원문Now squeeze about 1.4 times')
    expect(second).toHaveTextContent('이미지 프롬프트Animated compression')
    expect(second).toHaveTextContent('전환—')
    expect(within(section).getByText('장면 2개 · 합계 57.4초')).toBeInTheDocument()
  })

  it('lists recommendations separately from required blocks', async () => {
    api(detail('SUCCEEDED', () => 'SUCCEEDED'), on('GET', '/api/v1/production-requests/42/planning', 200, done))
    renderApp('/production/42')
    const user = userEvent.setup()
    const section = await screen.findByRole('region', { name: '자료 조사·대본·장면' })
    await user.click(within(section).getAllByText('검수 결과')[0])
    const findings = within(section).getByRole('list', { name: '검수 지적' })
    expect(findings).toHaveTextContent('개선 권고')
    expect(findings).toHaveTextContent('확인되지 않은 부가 내용 제외')
    expect(within(section).getByText('필수 차단 0건 · 개선 권고 1건')).toBeInTheDocument()
    expect(within(section).getAllByText('검수 지적 없음')).toHaveLength(2)
  })

  it('loads the final planning result when the detail becomes terminal between polls', async () => {
    let calls = 0
    api(detail('RUNNING', () => 'PENDING'), (call) => {
      if (call.method === 'GET' && call.url === '/api/v1/production-requests/42/planning') {
        return { status: 200, body: ++calls === 1 ? planning([]) : done }
      }
    })
    const { client } = renderApp('/production/42')
    expect(await screen.findByText('자료 조사 전입니다')).toBeInTheDocument()
    act(() => client.setQueryData(productionKey('42'), detail('SUCCEEDED', () => 'SUCCEEDED')))
    expect(await screen.findByRole('list', { name: '장면 목록' })).toBeInTheDocument()
  })

  it('explains a blocked research step and that later stages did not run', async () => {
    const blocked = planning([{
      operationId: '11', stage: 'RESEARCH', bodyHash: hash, body: { ...research, sources: [] },
      validation: { findings: [{ severity: 'BLOCK', code: 'CORE_EVIDENCE_INSUFFICIENT', location: 'research.claims[c2]', references: [], correction: 'Find a held source.' }] },
    }])
    api(
      detail('FAILED', (s) => (s === 'DISCOVER' || s === 'SELECT' ? 'SUCCEEDED' : s === 'RESEARCH' ? 'FAILED' : 'PENDING'),
        (s) => (s === 'RESEARCH' ? 'PLANNING_VALIDATION_BLOCKED' : null)),
      on('GET', '/api/v1/production-requests/42/planning', 200, blocked),
    )
    renderApp('/production/42')
    const section = await screen.findByRole('region', { name: '자료 조사·대본·장면' })
    expect(within(section).getByText(/자료 조사 검수에서 필수 차단 항목이 확인되어 이후 단계를 실행하지 않았습니다/)).toBeInTheDocument()
    const findings = within(section).getByRole('list', { name: '검수 지적' }) // opened automatically when blocked
    expect(findings).toHaveTextContent('필수 차단')
    expect(findings).toHaveTextContent('핵심 근거 부족')
    expect(within(section).getByText('보유한 출처가 없습니다.')).toBeInTheDocument()
    expect(within(section).queryByRole('list', { name: '대본 구간' })).not.toBeInTheDocument()
    expect(screen.getByRole('table', { name: '진행 단계' })).toHaveTextContent('검수 필수 차단(이후 단계 미실행)')
  })

  it('links only server-verified sources', async () => {
    const verified = { ...fixtureSource, id: 'r1', origin: 'RETRIEVED', status: 'VERIFIED', url: 'https://example.org/paper', title: 'Retrieved paper', checkedAtUtc: '2026-11-26T01:02:03Z' }
    const body = { ...research, sources: [verified, fixtureSource] }
    api(detail('SUCCEEDED', () => 'SUCCEEDED'), on('GET', '/api/v1/production-requests/42/planning', 200,
      planning([{ operationId: '11', stage: 'RESEARCH', bodyHash: hash, body, validation: { findings: [] } }])))
    renderApp('/production/42')
    const sources = await screen.findByRole('list', { name: '출처' })
    const link = within(sources).getByRole('link', { name: 'https://example.org/paper' })
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(within(sources).getByText('조회 확인')).toBeInTheDocument()
    expect(within(sources).getAllByRole('link')).toHaveLength(1)
  })

  it('tells an earlier request without stored bodies apart from one that has not reached research', async () => {
    api(detail('SUCCEEDED', () => 'SUCCEEDED'), on('GET', '/api/v1/production-requests/42/planning', 200, planning([])))
    const first = renderApp('/production/42')
    expect(await screen.findByText('저장된 조사·대본·장면 본문이 없는 요청입니다')).toBeInTheDocument()
    first.unmount()

    api(detail('RUNNING', (s) => (s === 'DISCOVER' ? 'SUCCEEDED' : 'PENDING')),
      on('GET', '/api/v1/production-requests/42/planning', 200, planning([])))
    renderApp('/production/42')
    expect(await screen.findByText('자료 조사 전입니다')).toBeInTheDocument()
  })

  it('shows a retryable error when the planning query fails', async () => {
    let attempts = 0
    api(detail('SUCCEEDED', () => 'SUCCEEDED'), (call) => {
      if (call.method === 'GET' && call.url === '/api/v1/production-requests/42/planning') {
        attempts++
        return attempts <= 3 ? { status: 503, body: error('SERVICE_UNAVAILABLE', 'busy') } : { status: 200, body: done }
      }
    })
    renderApp('/production/42')
    const user = userEvent.setup()
    // the shared query client retries server errors twice before showing the error
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 })
    await user.click(within(alert).getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('list', { name: '장면 목록' })).toBeInTheDocument()
  }, 15_000)

  it('shows permission errors from the planning query', async () => {
    api(detail('SUCCEEDED', () => 'SUCCEEDED'), on('GET', '/api/v1/production-requests/42/planning', 403, error('FORBIDDEN', 'no')))
    renderApp('/production/42')
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: '장면 목록' })).not.toBeInTheDocument()
  })
})

import type { Page, Request } from '@playwright/test'

/**
 * E2E용 API 대역. 실제 OpenAPI 응답 형태를 따르는 메모리 상태로 응답한다(실제 백엔드·DB·유료 호출 없음).
 * CSRF 헤더가 없거나 틀린 변경 요청은 403으로 거부해 화면의 CSRF 처리도 함께 검증한다.
 */
export interface Recorded {
  method: string
  path: string
  body: unknown
  headers: Record<string, string>
}

const NOW = '2026-11-25T03:00:00Z'

function page<T>(items: T[]) {
  return { page: 0, size: 20, totalElements: items.length, totalPages: 1, items }
}

function error(code: string, message: string) {
  return { code, message, traceId: 'e2e-trace', timestamp: NOW, details: [] }
}

export async function installMockApi(pageObject: Page, options: { signedIn?: boolean; emptyBudget?: boolean } = {}) {
  const state = {
    signedIn: options.signedIn ?? false,
    token: 'tok-0',
    spendingEnabled: false,
    controlVersion: 0,
    channels: [
      { id: '1', name: 'IFNOVA', language: 'ko', timezone: 'Asia/Seoul', status: 'ACTIVE', version: 2, currentConfigRevisionNo: 2, createdAt: '2026-11-20T01:00:00Z', updatedAt: '2026-11-24T05:00:00Z', archivedAt: null },
      { id: '2', name: 'Physics Lab', language: 'en', timezone: 'America/New_York', status: 'PAUSED', version: 1, currentConfigRevisionNo: 1, createdAt: '2026-11-21T01:00:00Z', updatedAt: '2026-11-22T05:00:00Z', archivedAt: null },
      { id: '3', name: '보관 채널', language: 'ko', timezone: 'Asia/Seoul', status: 'ARCHIVED', version: 3, currentConfigRevisionNo: 1, createdAt: '2026-11-01T01:00:00Z', updatedAt: '2026-11-10T05:00:00Z', archivedAt: '2026-11-10T05:00:00Z' },
    ] as Record<string, unknown>[],
  }
  const config = (channelId: string, revisionNo: number, extra: Record<string, unknown> = {}) => ({
    defaultContentType: 'LONGFORM', shortsMinSeconds: 40, shortsMaxSeconds: 60, longformMinSeconds: 480, longformMaxSeconds: 720,
    contentStrategy: '일상 속 과학 현상을 실험으로 설명', targetAudience: '중고등학생', narrationStyle: '차분하게', visualStyle: null,
    syntheticDisclosureDefault: true, providerOptions: null, channelId, revisionNo, changeNote: revisionNo === 1 ? 'initial' : '길이 조정',
    createdBy: '1', createdAt: revisionNo === 1 ? '2026-11-20T01:00:00Z' : '2026-11-24T05:00:00Z', ...extra,
  })
  const buckets = options.emptyBudget ? [] : [
    { id: '10', policyId: '1', scope: 'SYSTEM', channelId: null, periodType: 'DAY', periodStart: '2026-11-24T15:00:00Z', periodEnd: '2026-11-25T15:00:00Z', timezone: 'Asia/Seoul', limitKrw: '30000.000000', reservedKrw: '4200.370371', spentKrw: '21000.000000', remainingKrw: '4799.629629' },
    { id: '11', policyId: '2', scope: 'SYSTEM', channelId: null, periodType: 'MONTH', periodStart: '2026-10-31T15:00:00Z', periodEnd: '2026-11-30T15:00:00Z', timezone: 'Asia/Seoul', limitKrw: '300000.000000', reservedKrw: '4200.370371', spentKrw: '126450.5', remainingKrw: '169349.129629' },
  ]
  const policies = options.emptyBudget ? [] : [
    { id: '1', scope: 'SYSTEM', channelId: null, contentType: null, periodType: 'DAY', status: 'ACTIVE', version: 0, currentRevision: { revisionNo: 1, limitKrw: '30000.000000', timezone: 'Asia/Seoul', timezoneEffectiveFrom: '2026-11-20T00:00:00Z', changeNote: null, createdBy: '1', createdAt: '2026-11-20T00:00:00Z' }, createdAt: '2026-11-20T00:00:00Z', updatedAt: '2026-11-20T00:00:00Z' },
    { id: '2', scope: 'SYSTEM', channelId: null, contentType: null, periodType: 'MONTH', status: 'ACTIVE', version: 0, currentRevision: { revisionNo: 1, limitKrw: '300000.000000', timezone: 'Asia/Seoul', timezoneEffectiveFrom: '2026-11-20T00:00:00Z', changeNote: null, createdBy: '1', createdAt: '2026-11-20T00:00:00Z' }, createdAt: '2026-11-20T00:00:00Z', updatedAt: '2026-11-20T00:00:00Z' },
    { id: '3', scope: 'CHANNEL', channelId: '1', contentType: null, periodType: 'DAY', status: 'ACTIVE', version: 1, currentRevision: { revisionNo: 2, limitKrw: '0', timezone: 'Asia/Seoul', timezoneEffectiveFrom: '2026-11-20T00:00:00Z', changeNote: '점검 중 차단', createdBy: '1', createdAt: '2026-11-24T00:00:00Z' }, createdAt: '2026-11-20T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z' },
  ]
  const reservation = {
    id: '41', requestKey: 'img-ifnova-0001', channelId: '1', purpose: 'image', originalCurrency: 'USD', estimateOriginal: '1.00', exchangeRateId: '5', fxRate: '1400.1234567891', estimateKrw: '1400.123457', status: 'RECONCILIATION_REQUIRED', reservedRemainingKrw: '280.024692', recognizedCostKrw: '1120.098765', recognizedCostOriginal: '0.80', reconciliationReason: 'provider timeout', createdAt: '2026-11-25T01:00:00Z', updatedAt: '2026-11-25T01:05:00Z', closedAt: null,
  }
  const events = [
    { id: '100', eventKey: 'reserve:img-ifnova-0001', reservationId: '41', requestKey: 'img-ifnova-0001', entryType: 'RESERVE', reasonCode: null, originalCurrency: 'USD', originalAmount: '1.00', fxRate: '1400.1234567891', exchangeRateId: '5', amountKrw: '1400.123457', reserveDeltaKrw: '1400.123457', costDeltaKrw: '0', costDeltaOriginal: '0', priceVersion: null, externalRef: null, actorType: 'SYSTEM', actorUserId: null, occurredAt: '2026-11-25T01:00:00Z', recordedAt: '2026-11-25T01:00:00Z' },
    { id: '101', eventKey: 'evt-2', reservationId: '41', requestKey: 'img-ifnova-0001', entryType: 'PROVISIONAL', reasonCode: null, originalCurrency: 'USD', originalAmount: '0.80', fxRate: '1400.1234567891', exchangeRateId: '5', amountKrw: '1120.098765', reserveDeltaKrw: '-1120.098765', costDeltaKrw: '1120.098765', costDeltaOriginal: '0.80', priceVersion: null, externalRef: null, actorType: 'SYSTEM', actorUserId: null, occurredAt: '2026-11-25T01:02:00Z', recordedAt: '2026-11-25T01:02:00Z' },
  ]
  const recorded: Recorded[] = []

  // V2-02 제작 요청: 직접 선택 모드에서 후보 평가가 끝나 선택을 기다리는 요청 #42
  const stages = ['DISCOVER', 'SELECT', 'RESEARCH', 'SCRIPT', 'SCENES', 'IMAGE', 'TTS', 'SUBTITLE', 'RENDER', 'METADATA', 'QC']
  const productionState = { chosen: null as number | null, version: 1 }
  const scores = { interest: 25, channelFit: 20, originality: 15, visualPotential: 11, evidencePotential: 15, costEfficiency: 5 }
  const candidate = (id: number, title: string, question: string, blockCodes: string[], total: number) => ({
    candidateId: id,
    candidate: {
      coreTopic: title, centralQuestion: question, title, angle: 'Walk through the mechanism one step at a time with a simple diagram.',
      outline: [`Hook: pose the question over a diagram of ${title.toLowerCase()}`, 'Step-by-step diagram of the mechanism', 'Close with one open question for viewers'],
      evidencePlan: [{ sourceType: 'SPACE_AGENCY', searchQuestion: `What do current sources say about ${title.toLowerCase()}?` }, { sourceType: 'TEXTBOOK', searchQuestion: 'Which simple diagram explains it accurately?' }],
      assertions: [], suggestedScores: scores, rationale: 'FAKE candidate',
    },
    scores, totalScore: total, blockCodes,
    comparisons: blockCodes.length ? [{ topicId: '7', contentId: '8', revisionId: '9', reason: 'QUESTION_SIMILARITY 0.86 ANGLE_OVERLAP 0.71 DUPLICATE' }] : [],
    rationale: `rules v1: total ${total}/100 (interest 25, channel fit 20, originality 15, visual 11, evidence 15, cost 5); max similarity 0.12`,
  })
  const productionDiscovery = () => ({
    selectionMode: 'MANUAL', candidateCount: 5, version: productionState.version,
    awaitingSelection: productionState.chosen === null, chosenCandidateId: productionState.chosen, selectedCandidateId: null, selectionReason: null,
    candidates: [
      candidate(1, 'Why the Sky Is Blue, Not Violet', 'Why is the daytime sky blue instead of violet?', ['DUPLICATE'], 76),
      candidate(2, 'A Teaspoon of Neutron Star', 'How heavy would a teaspoon of neutron star be?', [], 91),
      candidate(3, 'A Day on Venus Outlasts Its Year', 'Why is a day on Venus longer than its year?', [], 88),
      candidate(4, 'How Tardigrades Survive Drying Out', 'How do tardigrades survive being completely dried out?', ['UNSUPPORTED_ASSERTION'], 85),
      candidate(5, 'The Compass Inside a Bird', 'How do migrating birds sense the magnetic field?', [], 80),
    ],
  })
  const productionDetail = () => ({
    id: '42', channelId: '1', contentType: 'SHORTS', status: 'RUNNING', contentId: null, runId: null, cancelRequestedAt: null,
    limitKrw: '2000.000000', reservedKrw: '0', recognizedKrw: '1.000000',
    steps: stages.map((stage) => ({ stage, targetKey: 'video', inputGeneration: 1, status: stage === 'DISCOVER' ? 'SUCCEEDED' : 'PENDING', attempts: stage === 'DISCOVER' ? 1 : 0 })),
  })
  const handleProduction = (method: string, path: string, body: Record<string, unknown> | undefined): { status: number; body?: unknown } | null => {
    if (path === '/api/v1/production-requests' && method === 'POST') return { status: 201, body: { id: '42', status: 'QUEUED' } }
    if (path === '/api/v1/production-requests/42' && method === 'GET') return { status: 200, body: productionDetail() }
    if (path === '/api/v1/production-requests/42/discovery') return { status: 200, body: productionDiscovery() }
    if (path === '/api/v1/production-requests/42/selection' && method === 'POST') {
      if (body?.version !== productionState.version) return { status: 409, body: error('RESOURCE_CONFLICT', 'stale version') }
      productionState.chosen = Number(body?.candidateId)
      productionState.version += 1
      return { status: 200, body: productionDiscovery() }
    }
    return null
  }

  const handle = async (request: Request): Promise<{ status: number; body?: unknown }> => {
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const body = request.postData() ? (JSON.parse(request.postData() as string) as Record<string, unknown>) : undefined
    const headers = request.headers()
    recorded.push({ method, path, body, headers })
    if (path === '/actuator/health') return { status: 200, body: { status: 'UP' } }
    if (path === '/api/v1/auth/csrf') return { status: 200, body: { headerName: 'X-XSRF-TOKEN', token: state.token } }
    if (method !== 'GET' && headers['x-xsrf-token'] !== state.token) return { status: 403, body: error('FORBIDDEN', 'Invalid CSRF token.') }
    if (path === '/api/v1/auth/login') {
      if (body?.password !== 'correct-horse-battery-1') return { status: 401, body: error('UNAUTHORIZED', 'Invalid login or password.') }
      state.signedIn = true
      state.token = 'tok-1'
      return { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: NOW } }
    }
    if (!state.signedIn) return { status: 401, body: error('UNAUTHORIZED', 'Authentication required.') }
    if (path === '/api/v1/auth/logout') {
      state.signedIn = false
      state.token = 'tok-2'
      return { status: 204 }
    }
    if (path === '/api/v1/auth/me') return { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: NOW } }
    if (path === '/api/v1/dashboard/summary') {
      return { status: 200, body: { channelCounts: { active: 1, paused: 1, archived: 1 }, systemUsage: buckets, reservationsRequiringReconciliation: 1, spendingEnabled: state.spendingEnabled, consistency: { status: 'OK', checkedAt: '2026-11-25T02:00:00Z', issueCount: 0 }, generatedAt: NOW } }
    }
    if (path === '/api/v1/channels' && method === 'GET') {
      const status = url.searchParams.get('status')
      const items = state.channels.filter((c) => !status || c.status === status)
      return { status: 200, body: { ...page(items), size: Number(url.searchParams.get('size') ?? 20) } }
    }
    if (path === '/api/v1/channels' && method === 'POST') {
      const created = { id: String(state.channels.length + 1), name: body?.name, language: body?.language, timezone: body?.timezone, status: 'ACTIVE', version: 0, currentConfigRevisionNo: 1, createdAt: NOW, updatedAt: NOW, archivedAt: null }
      state.channels.push(created)
      return { status: 201, body: created }
    }
    let m = /^\/api\/v1\/channels\/(\d+)$/.exec(path)
    if (m) return { status: 200, body: state.channels.find((c) => c.id === m![1]) }
    m = /^\/api\/v1\/channels\/(\d+)\/config$/.exec(path)
    if (m) return { status: 200, body: config(m[1], Number(state.channels.find((c) => c.id === m![1])?.currentConfigRevisionNo ?? 1)) }
    m = /^\/api\/v1\/channels\/(\d+)\/config\/revisions$/.exec(path)
    if (m && method === 'GET') return { status: 200, body: page([config(m[1], 2), config(m[1], 1, { longformMaxSeconds: 600, narrationStyle: null })]) }
    if (path === '/api/v1/budget-policies' && method === 'GET') {
      const scope = url.searchParams.get('scope')
      const status = url.searchParams.get('status')
      return { status: 200, body: page(policies.filter((p) => (!scope || p.scope === scope) && (!status || p.status === status))) }
    }
    if (path === '/api/v1/budget-policies' && method === 'POST') {
      return { status: 201, body: { ...policies[0], id: '9', scope: body?.scope, channelId: body?.channelId ?? null, currentRevision: { ...policies[0].currentRevision, limitKrw: body?.limitKrw } } }
    }
    m = /^\/api\/v1\/budget-policies\/(\d+)$/.exec(path)
    if (m) return { status: 200, body: policies.find((p) => p.id === m![1]) ?? policies[0] }
    if (/^\/api\/v1\/budget-policies\/\d+\/revisions$/.test(path)) return { status: 200, body: page([policies[0].currentRevision]) }
    if (/^\/api\/v1\/budget-policies\/\d+\/buckets$/.test(path)) return { status: 200, body: page(buckets.slice(0, 1)) }
    if (path === '/api/v1/budget/usage') return { status: 200, body: { at: NOW, channelId: url.searchParams.get('channelId'), buckets } }
    if (path === '/api/v1/budget/control' && method === 'GET') {
      return { status: 200, body: { spendingEnabled: state.spendingEnabled, spendingChangedAt: null, spendingChangedBy: null, spendingChangeReason: null, consistency: { status: 'OK', checkedAt: '2026-11-25T02:00:00Z', issueCount: 0 }, version: state.controlVersion, updatedAt: NOW } }
    }
    if (path === '/api/v1/budget/control/spending') {
      state.spendingEnabled = Boolean(body?.enabled)
      state.controlVersion += 1
      return { status: 200, body: { spendingEnabled: state.spendingEnabled, spendingChangedAt: NOW, spendingChangedBy: '1', spendingChangeReason: body?.reason, consistency: { status: 'OK', checkedAt: NOW, issueCount: 0 }, version: state.controlVersion, updatedAt: NOW } }
    }
    if (path === '/api/v1/budget/consistency-checks') return { status: 200, body: { checkedAt: NOW, consistent: true, issues: [] } }
    if (path === '/api/v1/budget-reservations') return { status: 200, body: page([reservation]) }
    if (/^\/api\/v1\/budget-reservations\/\d+$/.test(path)) return { status: 200, body: { ...reservation, bucketIds: ['10', '11'], events } }
    if (path === '/api/v1/cost-ledger') return { status: 200, body: page(events) }
    if (path === '/api/v1/exchange-rates' && method === 'GET') {
      return { status: 200, body: page([{ id: '5', baseCurrency: 'USD', quoteCurrency: 'KRW', rate: '1400.1234567891', effectiveFrom: new Date(Date.now() - 3_600_000).toISOString(), validUntil: new Date(Date.now() + 71 * 3_600_000).toISOString(), source: 'MANUAL', note: '은행 고시', createdBy: '1', createdAt: NOW }]) }
    }
    if (path === '/api/v1/exchange-rates' && method === 'POST') {
      return { status: 201, body: { id: '6', baseCurrency: body?.baseCurrency, quoteCurrency: 'KRW', rate: body?.rate, effectiveFrom: NOW, validUntil: NOW, source: 'MANUAL', note: null, createdBy: '1', createdAt: NOW } }
    }
    const production = handleProduction(method, path, body)
    if (production) return production
    return { status: 404, body: error('RESOURCE_NOT_FOUND', `No mock for ${method} ${path}`) }
  }

  // 경로가 /api/ 또는 /actuator/로 시작하는 요청만(소스 모듈 /src/api/*.ts는 제외)
  await pageObject.route((url) => url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/'), async (route) => {
    const result = await handle(route.request())
    await route.fulfill({
      status: result.status,
      contentType: 'application/json',
      body: result.body === undefined ? '' : JSON.stringify(result.body),
    })
  })
  return { recorded, state }
}

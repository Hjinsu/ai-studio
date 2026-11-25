import type { components } from './generated/schema'
import { advanceSession, assertSession, currentSession, request, type Query } from './http'

/** 관리자 API (docs/api/openapi.yaml). 타입은 생성 타입(src/api/generated)만 사용한다. */
export type Schemas = components['schemas']
export type Channel = Schemas['Channel']
export type ChannelConfig = Schemas['ChannelConfig']
export type ChannelConfigSettings = Schemas['ChannelConfigSettings']
export type BudgetPolicy = Schemas['BudgetPolicy']
export type BudgetBucket = Schemas['BudgetBucket']
export type Reservation = Schemas['Reservation']
export type ReservationDetail = Schemas['ReservationDetail']
export type CostLogEntry = Schemas['CostLogEntry']
export type ExchangeRate = Schemas['ExchangeRate']
export type BudgetControl = Schemas['BudgetControl']

const V1 = '/api/v1'

export interface PageQuery {
  page?: number
  size?: number
}

// ---------------------------------------------------------------- 인증
export async function login(body: Schemas['LoginRequest']): Promise<Schemas['CurrentUser']> {
  const generation = currentSession()
  const user = await request<Schemas['CurrentUser']>('POST', `${V1}/auth/login`, { body })
  assertSession(generation)
  advanceSession()
  return user
}

export async function logout(): Promise<void> {
  advanceSession()
  const generation = currentSession()
  try {
    await request<void>('POST', `${V1}/auth/logout`)
  } finally {
    assertSession(generation)
    advanceSession()
  }
}

export const currentUser = () => request<Schemas['CurrentUser']>('GET', `${V1}/auth/me`)
export const dashboardSummary = () => request<Schemas['DashboardSummary']>('GET', `${V1}/dashboard/summary`)

// ---------------------------------------------------------------- 채널
export const listChannels = (query: PageQuery & { status?: string }) =>
  request<Schemas['ChannelPage']>('GET', `${V1}/channels`, { query: query as Query })
export const getChannel = (id: string) => request<Channel>('GET', `${V1}/channels/${id}`)
export const createChannel = (body: Schemas['ChannelCreateRequest']) =>
  request<Channel>('POST', `${V1}/channels`, { body })
export const updateChannel = (id: string, body: Schemas['ChannelUpdateRequest']) =>
  request<Channel>('PATCH', `${V1}/channels/${id}`, { body })
export const changeChannelStatus = (id: string, action: 'pause' | 'resume' | 'archive', version: number) =>
  request<Channel>('POST', `${V1}/channels/${id}/${action}`, { body: { version } })
export const currentChannelConfig = (id: string) => request<ChannelConfig>('GET', `${V1}/channels/${id}/config`)
export const listChannelConfigs = (id: string, query: PageQuery) =>
  request<Schemas['ChannelConfigPage']>('GET', `${V1}/channels/${id}/config/revisions`, { query: query as Query })
export const createChannelConfig = (id: string, body: Schemas['ChannelConfigCreateRequest']) =>
  request<ChannelConfig>('POST', `${V1}/channels/${id}/config/revisions`, { body })

// ---------------------------------------------------------------- 예산 정책·사용량·제어
export const listPolicies = (query: PageQuery & { scope?: string; channelId?: string; status?: string }) =>
  request<Schemas['BudgetPolicyPage']>('GET', `${V1}/budget-policies`, { query: query as Query })
export const getPolicy = (id: string) => request<BudgetPolicy>('GET', `${V1}/budget-policies/${id}`)
export const createPolicy = (body: Schemas['BudgetPolicyCreateRequest']) =>
  request<BudgetPolicy>('POST', `${V1}/budget-policies`, { body })
export const listPolicyRevisions = (id: string, query: PageQuery) =>
  request<Schemas['BudgetPolicyRevisionPage']>('GET', `${V1}/budget-policies/${id}/revisions`, { query: query as Query })
export const revisePolicy = (id: string, body: Schemas['BudgetPolicyRevisionCreateRequest']) =>
  request<BudgetPolicy>('POST', `${V1}/budget-policies/${id}/revisions`, { body })
export const setPolicyEnabled = (id: string, enabled: boolean, version: number) =>
  request<BudgetPolicy>('POST', `${V1}/budget-policies/${id}/${enabled ? 'enable' : 'disable'}`, { body: { version } })
export const listBuckets = (id: string, query: PageQuery) =>
  request<Schemas['BudgetBucketPage']>('GET', `${V1}/budget-policies/${id}/buckets`, { query: query as Query })
export const budgetUsage = (channelId?: string) =>
  request<Schemas['BudgetUsage']>('GET', `${V1}/budget/usage`, { query: { channelId } })
export const budgetControl = () => request<BudgetControl>('GET', `${V1}/budget/control`)
export const setSpending = (body: Schemas['SpendingChangeRequest']) =>
  request<BudgetControl>('POST', `${V1}/budget/control/spending`, { body })
export const runConsistencyCheck = () => request<Schemas['ConsistencyReport']>('POST', `${V1}/budget/consistency-checks`)

// ---------------------------------------------------------------- 예약·원장
export const listReservations = (query: PageQuery & { status?: string; channelId?: string }) =>
  request<Schemas['ReservationPage']>('GET', `${V1}/budget-reservations`, { query: query as Query })
export const getReservation = (id: string) => request<ReservationDetail>('GET', `${V1}/budget-reservations/${id}`)
/** 멱등 키는 호출자가 한 번의 사용자 작업 동안 유지한다(재시도 시 같은 키 → 중복 기록 없음). */
export const resolveReservation = (id: string, idempotencyKey: string, body: Schemas['ReservationResolveRequest']) =>
  request<ReservationDetail>('POST', `${V1}/budget-reservations/${id}/resolve`, {
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
export const adjustReservation = (id: string, idempotencyKey: string, body: Schemas['ReservationAdjustRequest']) =>
  request<ReservationDetail>('POST', `${V1}/budget-reservations/${id}/adjustments`, {
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
export const listCostLedger = (
  query: PageQuery & { channelId?: string; reservationId?: string; requestKey?: string; entryType?: string },
) => request<Schemas['CostLogPage']>('GET', `${V1}/cost-ledger`, { query: query as Query })

// ---------------------------------------------------------------- 환율
export const listExchangeRates = (query: PageQuery & { baseCurrency?: string }) =>
  request<Schemas['ExchangeRatePage']>('GET', `${V1}/exchange-rates`, { query: query as Query })
export const createExchangeRate = (body: Schemas['ExchangeRateCreateRequest']) =>
  request<ExchangeRate>('POST', `${V1}/exchange-rates`, { body })

export function newIdempotencyKey(): string {
  return `ui-${crypto.randomUUID()}`
}

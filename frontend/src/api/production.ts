import type { components } from './generated/schema'
import { request } from './http'

/** 제작 요청·소재 발굴 API (docs/api/openapi.yaml, V2-01·V2-02). 타입은 생성 타입만 사용한다. */
type Schemas = components['schemas']
export type ProductionRequestDetail = Schemas['ProductionRequestDetail']
export type ProductionRequestSummary = Schemas['ProductionRequestSummary']
export type ProductionStatus = Schemas['ProductionStatus']
export type ProductionStep = ProductionRequestDetail['steps'][number]
export type ProductionDiscovery = Schemas['ProductionDiscoveryDetail']
export type EvaluatedCandidate = Schemas['EvaluatedDiscoveryCandidate']
export type DiscoveryScores = Schemas['DiscoveryScores']
export type BlockCode = EvaluatedCandidate['blockCodes'][number]
export type SelectionMode = ProductionDiscovery['selectionMode']
export type ContentType = ProductionRequestDetail['contentType']

export interface CreateProductionRequest {
  channelId: string
  contentType: ContentType
  selectionMode?: SelectionMode
  candidateCount?: number
}

const PATH = '/api/v1/production-requests'

export const MIN_CANDIDATES = 5
export const MAX_CANDIDATES = 10
export const DEFAULT_CANDIDATES = 8

/** 멱등 키는 호출자가 한 번의 제작 시작 작업 동안 유지한다(재시도 시 같은 요청을 재생). */
export const createProductionRequest = (idempotencyKey: string, body: CreateProductionRequest) =>
  request<ProductionRequestSummary>('POST', PATH, { body, headers: { 'Idempotency-Key': idempotencyKey } })
export const getProductionRequest = (id: string) => request<ProductionRequestDetail>('GET', `${PATH}/${id}`)
export const cancelProductionRequest = (id: string) => request<ProductionRequestDetail>('POST', `${PATH}/${id}/cancel`)
export const getProductionDiscovery = (id: string) => request<ProductionDiscovery>('GET', `${PATH}/${id}/discovery`)
export const chooseProductionCandidate = (id: string, candidateId: number, version: number) =>
  request<ProductionDiscovery>('POST', `${PATH}/${id}/selection`, { body: { candidateId, version } })

export const productionKey = (id: string) => ['production-request', id] as const
export const discoveryKey = (id: string) => ['production-request', id, 'discovery'] as const

export function isTerminal(status: ProductionStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED'
}

import type { components } from './generated/schema'
import { readJson, toApiError } from './client'
import { assertSession, currentSession, request } from './http'

/** 제작 요청·소재 발굴·기획 결과 API (docs/api/openapi.yaml, V2-01~V2-03). 타입은 생성 타입만 사용한다. */
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
export type ProductionRequestPage = Schemas['ProductionRequestPage']
export type ProductionRequestListItem = Schemas['ProductionRequestListItem']
export type ProductionPlanning = Schemas['ProductionPlanning']
export type PlanningResult = Schemas['PlanningResult']
export type PlanningFinding = Schemas['PlanningFinding']
export type PlanningResearch = Schemas['PlanningResearch']
export type PlanningClaim = Schemas['PlanningClaim']
export type PlanningSource = Schemas['PlanningSource']
export type PlanningScript = Schemas['PlanningScript']
export type PlanningScenes = Schemas['PlanningScenes']
export type PlanningScene = Schemas['PlanningScene']

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
/** 적용된 조사·대본·장면 본문과 검수 지적(V2-03). 과거 요청과 미실행 단계는 결과가 비어 있다. */
export const getProductionPlanning = (id: string) => request<ProductionPlanning>('GET', `${PATH}/${id}/planning`)
export const listProductionRequests = (page: number, size = 20) =>
  request<ProductionRequestPage>('GET', PATH, { query: { page, size } })

/** 사용자가 확인한 후보 묶음(version·batchToken)을 함께 보낸다. 묶음이 바뀌었으면 서버가 409로 거부한다. */
export interface CandidateChoice {
  candidateId: number
  version: number
  batchToken: string
  confirmReview?: boolean
}
export const chooseProductionCandidate = (id: string, choice: CandidateChoice) =>
  request<ProductionDiscovery>('POST', `${PATH}/${id}/selection`, { body: choice })

export const productionListKey = (page: number) => ['production-requests', page] as const
export const productionKey = (id: string) => ['production-request', id] as const
export const discoveryKey = (id: string) => ['production-request', id, 'discovery'] as const
export const planningKey = (id: string) => ['production-request', id, 'planning'] as const

export function isTerminal(status: ProductionStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED'
}

// ------------------------------------------------------------------ V2-04 media (FAKE/local)

export type ProductionMedia = Schemas['ProductionMedia']
export type ProductionMediaAsset = ProductionMedia['assets'][number]
export type MediaRole = ProductionMediaAsset['role']

export const mediaKey = (id: string) => ['production-request', id, 'media'] as const
export const mediaFileKey = (id: string, assetId: string) => ['production-request', id, 'media', assetId] as const

/** 공개 가능한(확정된) 미디어 목록과 기술 QC 단계 상태. 최종 MP4·QC 보고서·manifest는 QC 통과 후에만 포함된다. */
export const getProductionMedia = (id: string) => request<ProductionMedia>('GET', `${PATH}/${id}/media`)

/** 같은 origin의 세션 쿠키로 스트리밍한다(브라우저의 video/audio가 Range 요청으로 seek). URL에 토큰을 넣지 않는다. */
export function mediaUrl(id: string, assetId: string, download = false): string {
  const path = `${PATH}/${encodeURIComponent(id)}/media/${encodeURIComponent(assetId)}`
  return download ? `${path}?download=true` : path
}

/** QC 보고서·manifest(JSON). 오류 처리와 세션 만료는 공통 호출기를 따른다. */
export const getProductionMediaJson = <T>(id: string, assetId: string) =>
  request<T>('GET', `${PATH}/${id}/media/${assetId}`)

/** SRT 같은 텍스트 파일. 실패는 공통 ApiError로 바꿔 세션 만료 처리 경로를 그대로 탄다. */
export async function getProductionMediaText(id: string, assetId: string): Promise<string> {
  const generation = currentSession()
  const response = await fetch(mediaUrl(id, assetId), {
    credentials: 'same-origin',
    headers: { Accept: 'application/x-subrip, text/plain' },
    signal: generation,
  })
  assertSession(generation)
  if (!response.ok) {
    const error = toApiError(response.status, await readJson(response))
    error.generation = generation
    throw error
  }
  const text = await response.text()
  assertSession(generation)
  return text
}

/** QC 보고서(서버 qc-1 형식)에서 화면에 쓰는 부분. 수치는 표시용이며 판정은 서버 status/result를 그대로 쓴다. */
export interface MediaQcCheck {
  code: string
  target: string
  result: 'PASS' | 'WARN' | 'BLOCK'
  measured: string
  expected: string
}
export interface MediaQcReport {
  status: string
  checker: string
  frames: number
  durationSeconds: number
  narrationSeconds: number
  integratedLufs: number
  truePeakDbtp: number
  normalizationType: string | null
  checks: MediaQcCheck[]
}

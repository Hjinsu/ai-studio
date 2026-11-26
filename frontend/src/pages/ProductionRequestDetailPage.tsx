import { useState } from 'react'
import { useParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Hourglass, Lightbulb, XCircle } from 'lucide-react'
import { ApiError } from '../api/client'
import { assertSession } from '../api/http'
import { useChannelName } from '../api/useChannels'
import { invalidateSessionQueries, useSessionMutation } from '../api/useSessionMutation'
import {
  cancelProductionRequest,
  chooseProductionCandidate,
  discoveryKey,
  getProductionDiscovery,
  getProductionRequest,
  isTerminal,
  productionKey,
  type CandidateChoice,
  type EvaluatedCandidate,
  type ProductionDiscovery,
  type ProductionRequestDetail,
  type ProductionStep,
} from '../api/production'
import { cn } from '../lib/cn'
import { CONTENT_TYPE, label, text } from '../lib/labels'
import { Button } from '../ui/Button'
import { Card, StatCard } from '../ui/Card'
import { DataTable } from '../ui/DataTable'
import { ConfirmDialog } from '../ui/Dialog'
import { DateTime, Money } from '../ui/Display'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, ErrorState, InlineNotice, LoadingSkeleton, QueryView } from '../ui/States'
import { LabelBadge, StatusBadge } from '../ui/StatusBadge'
import { useToast } from '../ui/toast-context'
import {
  BLOCK,
  CRITERIA,
  FAKE_RUN_NOTICE,
  SELECTION_MODE,
  SOURCE_TYPE,
  STAGE,
  STEP_ERROR,
  STEP_STATUS,
  productionStatusLabel,
} from './ProductionRequestLabels'

/** 진행 중인 요청은 주기적으로 다시 조회한다(완료·실패·취소 후 중지). */
const POLL_MS = 5_000

/**
 * 제작 요청 상세(V2-02): 진행 단계·비용, 소재 후보와 평가 근거, 선정 결과. 직접 선택 모드에서 대기 중이면 적격 후보를
 * 고를 수 있다. 저장된 결과만 보여주며 화면을 다시 열어도 같은 대기 상태를 이어서 확인할 수 있다.
 */
export function ProductionRequestDetailPage() {
  const { requestId = '' } = useParams()
  const queryClient = useQueryClient()
  const toast = useToast()
  const channelName = useChannelName()
  const detail = useQuery({
    queryKey: productionKey(requestId),
    queryFn: () => getProductionRequest(requestId),
    refetchInterval: (query) => (query.state.data && isTerminal(query.state.data.status) ? false : POLL_MS),
  })
  const [cancelling, setCancelling] = useState(false)
  const refresh = (generation: AbortSignal) =>
    invalidateSessionQueries(queryClient, generation, productionKey(requestId), discoveryKey(requestId))
  const cancel = useSessionMutation({
    mutationFn: () => cancelProductionRequest(requestId),
    onSuccess: async (_result, _variables, generation) => {
      assertSession(generation)
      setCancelling(false)
      toast.success('취소를 요청했습니다. 진행 중인 단계를 정리한 뒤 취소됩니다.', generation)
      await refresh(generation)
    },
  })

  return (
    <QueryView query={detail} rows={6}>
      {(request) => {
        const status = productionStatusLabel(request.status)
        const open = !isTerminal(request.status) && !request.cancelRequestedAt
        return (
          <>
            <PageHeader
              title={`제작 요청 #${request.id}`}
              meta={<LabelBadge value={status} />}
              description={`${channelName(request.channelId)} · ${text(CONTENT_TYPE, request.contentType)}`}
              actions={
                open && (
                  <Button variant="danger" onClick={() => { cancel.reset(); setCancelling(true) }}>
                    제작 취소…
                  </Button>
                )
              }
            />
            <div className="grid grid-cols-1 gap-5">
              {request.cancelRequestedAt && !isTerminal(request.status) && (
                <InlineNotice tone="warning">
                  <DateTime value={request.cancelRequestedAt} />에 취소를 요청했습니다. 실행 중인 단계와 비용 확인이 끝나면 취소됩니다.
                </InlineNotice>
              )}
              {request.status === 'SUCCEEDED' && <InlineNotice tone="warning">{FAKE_RUN_NOTICE}</InlineNotice>}
              {request.status === 'RECOVERY_REQUIRED' && (
                <InlineNotice tone="warning">
                  외부 실행 결과 또는 비용 정산을 확인하는 중입니다. 예약은 유지되며 같은 작업을 다시 호출하지 않습니다.
                </InlineNotice>
              )}
              <section aria-label="비용" className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                <StatCard label="제작 요청 한도" value={<Money value={request.limitKrw} />} />
                <StatCard label="예약 중" value={<Money value={request.reservedKrw} />} />
                <StatCard label="확정 비용" value={<Money value={request.recognizedKrw} />} />
              </section>
              <DiscoverySection request={request} onChanged={refresh} />
              <Card title="진행 단계" description="고정된 11단계를 순서대로 실행합니다.">
                <DataTable<ProductionStep>
                  caption="진행 단계"
                  rows={request.steps}
                  rowKey={(step) => step.stage}
                  rowTone={(step) => (step.status === 'FAILED' ? 'danger' : step.status === 'RECOVERY_REQUIRED' ? 'warning' : undefined)}
                  columns={[
                    { key: 'stage', header: '단계', cell: (step) => text(STAGE, step.stage) },
                    { key: 'status', header: '상태', cell: (step) => <LabelBadge value={label(STEP_STATUS, step.status)} /> },
                    { key: 'attempts', header: '시도', align: 'right', cell: (step) => <span className="num">{step.attempts}회</span> },
                    { key: 'error', header: '마지막 오류', cell: (step) => <StepError code={step.lastErrorCode} /> },
                  ]}
                />
              </Card>
            </div>
            <ConfirmDialog
              open={cancelling}
              title="제작을 취소할까요?"
              confirmLabel="제작 취소"
              tone="danger"
              busy={cancel.isPending}
              onConfirm={() => cancel.mutate()}
              onCancel={() => setCancelling(false)}
            >
              <p>새 단계는 시작하지 않습니다. 이미 실행된 외부 작업의 비용은 결과를 확인한 뒤 정산됩니다.</p>
              {cancel.error && <ErrorState error={cancel.error} compact />}
            </ConfirmDialog>
          </>
        )
      }}
    </QueryView>
  )
}

function DiscoverySection({
  request,
  onChanged,
}: {
  request: ProductionRequestDetail
  onChanged: (generation: AbortSignal) => Promise<unknown>
}) {
  const discovery = useQuery({
    queryKey: discoveryKey(request.id),
    queryFn: () => getProductionDiscovery(request.id),
    retry: (failures, error) => !(error instanceof ApiError && error.status === 404) && failures < 2,
    refetchInterval: (query) =>
      isTerminal(request.status) || query.state.data?.selectedCandidateId != null ? false : POLL_MS,
  })
  if (discovery.isPending) {
    return (
      <Card title="소재 후보">
        <LoadingSkeleton rows={3} />
      </Card>
    )
  }
  if (discovery.isError) {
    if (discovery.error instanceof ApiError && discovery.error.status === 404) {
      return (
        <Card title="소재 후보">
          <EmptyState title="소재 발굴을 사용하지 않는 요청입니다" description="롱폼 또는 이전 방식으로 만든 요청은 고정 단계 경로로 소재를 연결합니다." />
        </Card>
      )
    }
    return (
      <Card title="소재 후보">
        <ErrorState error={discovery.error} onRetry={() => void discovery.refetch()} />
      </Card>
    )
  }
  return <DiscoveryCandidates request={request} discovery={discovery.data} onChanged={onChanged} />
}

function DiscoveryCandidates({
  request,
  discovery,
  onChanged,
}: {
  request: ProductionRequestDetail
  discovery: ProductionDiscovery
  onChanged: (generation: AbortSignal) => Promise<unknown>
}) {
  const toast = useToast()
  // 확인 대화상자를 열 때 본 후보 묶음(version·batchToken)을 고정한다. 폴링으로 묶음이 바뀌면 다시 고르게 한다.
  const [picking, setPicking] = useState<{ candidate: EvaluatedCandidate; choice: CandidateChoice } | null>(null)
  const choose = useSessionMutation({
    mutationFn: (choice: CandidateChoice) => chooseProductionCandidate(request.id, choice),
    onSuccess: async (_result, choice, generation) => {
      assertSession(generation)
      setPicking(null)
      toast.success(`후보 ${choice.candidateId}번을 선택했습니다. 다시 확인한 뒤 다음 단계로 진행합니다.`, generation)
      await onChanged(generation)
    },
  })
  const batchChanged =
    picking != null &&
    (picking.choice.version !== discovery.version || picking.choice.batchToken !== discovery.batchToken || !discovery.awaitingSelection)
  const mode = SELECTION_MODE[discovery.selectionMode] ?? { text: discovery.selectionMode, help: '' }
  const ordered = [...discovery.candidates].sort((a, b) => rank(b) - rank(a) || a.candidateId - b.candidateId)
  const eligible = ordered.filter((c) => c.blockCodes.length === 0)
  const reviewable = ordered.filter((c) => requiresReview(c) && !isBlocked(c))
  const selected = discovery.candidates.find((c) => c.candidateId === discovery.selectedCandidateId)
  const discoverStep = request.steps.find((step) => step.stage === 'DISCOVER')

  return (
    <Card
      title="소재 후보"
      description={`${mode.text} · 후보 ${discovery.candidateCount}개 · 자동 선정 가능 ${eligible.length}개 · 검수 필요 ${reviewable.length}개`}
    >
      <div className="grid grid-cols-1 gap-4">
        {discovery.candidates.length === 0 ? (
          <EmptyState
            icon={<Hourglass className="size-6" />}
            title={discoverStep?.status === 'FAILED' ? '소재 후보를 만들지 못했습니다' : '소재 후보를 발굴하는 중입니다'}
            description={
              discoverStep?.status === 'FAILED'
                ? '소재 발굴 단계가 실패했습니다. 비용 보호 규칙이나 응답 형식 검사로 중단되었을 수 있습니다.'
                : '평가가 끝나면 후보와 점수가 여기에 표시됩니다.'
            }
          />
        ) : (
          <>
            {discovery.awaitingSelection && (
              <InlineNotice tone="primary" icon={<Lightbulb className="size-4" />}>
                후보를 선택하면 최신 소재 목록과 다시 비교합니다. 검수 필요 후보는 비교 근거를 확인한 뒤 선택할 수 있습니다.
              </InlineNotice>
            )}
            {discovery.selectionMode === 'MANUAL' && discovery.chosenCandidateId != null && selected == null && (
              <InlineNotice>후보 {discovery.chosenCandidateId}번 선택을 기록했습니다. 다음 실행에서 다시 확인한 뒤 연결합니다.</InlineNotice>
            )}
            {eligible.length === 0 && reviewable.length === 0 && (
              <InlineNotice tone="danger">모든 후보가 차단되었습니다. 추가 발굴이나 자동 재생성은 하지 않습니다.</InlineNotice>
            )}
            {eligible.length === 0 && reviewable.length > 0 && discovery.selectionMode === 'AUTO' && (
              <InlineNotice tone="warning">자동 선정 가능한 후보가 없습니다. 검수 필요 후보는 자동 선정에서 제외됩니다.</InlineNotice>
            )}
            {selected && (
              <InlineNotice tone="primary" icon={<CheckCircle2 className="size-4" />}>
                <p className="font-medium">선정 소재: {selected.candidate.title}</p>
                {discovery.selectionReason && <p className="mt-1 text-sm break-words">{discovery.selectionReason}</p>}
              </InlineNotice>
            )}
            <ol aria-label="소재 후보 목록" className="grid grid-cols-1 gap-3">
              {ordered.map((candidate) => (
                <CandidateCard
                  key={candidate.candidateId}
                  candidate={candidate}
                  selected={candidate.candidateId === discovery.selectedCandidateId}
                  chosen={candidate.candidateId === discovery.chosenCandidateId}
                  canChoose={discovery.awaitingSelection && !isBlocked(candidate)}
                  onChoose={() => {
                    choose.reset()
                    setPicking({
                      candidate,
                      choice: {
                        candidateId: candidate.candidateId,
                        version: discovery.version,
                        batchToken: discovery.batchToken,
                        ...(requiresReview(candidate) ? { confirmReview: true } : {}),
                      },
                    })
                  }}
                />
              ))}
            </ol>
          </>
        )}
      </div>
      <ConfirmDialog
        open={picking != null}
        title="이 소재로 진행할까요?"
        confirmLabel={picking && requiresReview(picking.candidate) ? "검수 근거를 확인하고 선택" : "이 소재 선택"}
        tone="primary"
        busy={choose.isPending}
        confirmDisabled={batchChanged}
        onConfirm={() => picking && !batchChanged && choose.mutate(picking.choice)}
        onCancel={() => setPicking(null)}
      >
        {picking && (
          <>
            <p className="font-medium">{picking.candidate.candidate.title}</p>
            {requiresReview(picking.candidate) && (
              <InlineNotice tone="warning">
                <p>검수 필요. 비교 근거를 확인하고 이 소재에 독립적인 가치가 있는지 판단해 주세요.</p>
                <ul className="mt-1 list-disc pl-5">
                  {picking.candidate.comparisons.filter((item) => item.reason.endsWith(' SIMILARITY_UNCERTAIN'))
                    .map((item, index) => <li key={index}>{item.reason}</li>)}
                </ul>
              </InlineNotice>
            )}
            <p className="text-sm text-muted">
              선정 직전에 최신 소재·콘텐츠와 다시 비교합니다. 그사이 중복이 생기면 선택이 해제되고 다시 고를 수 있습니다.
            </p>
          </>
        )}
        {batchChanged && (
          <InlineNotice tone="warning">
            후보 평가가 바뀌었습니다. 이 창을 닫고 최신 후보 목록에서 다시 선택해 주세요.
          </InlineNotice>
        )}
        {choose.error && !batchChanged && <ErrorState error={choose.error} compact />}
      </ConfirmDialog>
    </Card>
  )
}

/** 화면 정렬용: 적격 후보 먼저, 그다음 서버 총점. 선정 판단은 서버가 한다. */
function rank(candidate: EvaluatedCandidate): number {
  return (isBlocked(candidate) ? 0 : requiresReview(candidate) ? 1000 : 2000) + candidate.totalScore
}

function requiresReview(candidate: EvaluatedCandidate): boolean {
  return candidate.blockCodes.includes('SIMILARITY_UNCERTAIN')
}

function isBlocked(candidate: EvaluatedCandidate): boolean {
  return candidate.blockCodes.some((code) => code !== 'SIMILARITY_UNCERTAIN')
}

function CandidateCard({
  candidate,
  selected,
  chosen,
  canChoose,
  onChoose,
}: {
  candidate: EvaluatedCandidate
  selected: boolean
  chosen: boolean
  canChoose: boolean
  onChoose: () => void
}) {
  const c = candidate.candidate
  const blocked = isBlocked(candidate)
  const review = requiresReview(candidate)
  const penalized = candidate.comparisons.some((item) => item.reason.endsWith(' SIMILARITY_PENALTY'))
  const headingId = `candidate-${candidate.candidateId}`
  return (
    <li
      aria-labelledby={headingId}
      className={cn(
        'grid gap-3 rounded-lg border p-4',
        selected ? 'border-primary bg-primary-soft' : blocked ? 'border-line bg-subtle' : 'border-line bg-surface',
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs text-muted">후보 {candidate.candidateId}번 · {c.coreTopic}</p>
          <h3 id={headingId} className="text-md font-semibold break-words">
            {c.title}
          </h3>
          <p className="mt-0.5 text-sm break-words">{c.centralQuestion}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {selected && <StatusBadge tone="primary" icon={<CheckCircle2 aria-hidden className="size-3" />}>선정됨</StatusBadge>}
          {chosen && !selected && <StatusBadge tone="primary">선택함</StatusBadge>}
          {penalized && <StatusBadge tone="warning">독창성 감점</StatusBadge>}
          {blocked && <StatusBadge tone="danger">차단</StatusBadge>}
          {blocked || review ? (
            candidate.blockCodes.map((code) => <LabelBadge key={code} value={BLOCK[code] ?? label({}, code)} />)
          ) : (
            <StatusBadge tone="success">적격</StatusBadge>
          )}
          <span className="num text-lg font-semibold" aria-label={`총점 ${candidate.totalScore}점(100점 만점)`}>
            {candidate.totalScore}
            <span className="text-sm font-normal text-muted">/100</span>
          </span>
        </div>
      </div>
      <dl className="grid max-w-2xl grid-cols-2 gap-x-6 gap-y-1.5 text-sm sm:grid-cols-3">
        {CRITERIA.map((criterion) => (
          <div key={criterion.key} className="flex justify-between gap-2">
            <dt className="text-muted">{criterion.text}</dt>
            <dd className="num">
              {candidate.scores[criterion.key]}/{criterion.max}
            </dd>
          </div>
        ))}
      </dl>
      <details className="text-sm">
        <summary className="cursor-pointer font-medium">설명 관점·구성안·근거 계획</summary>
        <div className="mt-2 grid gap-3">
          <p>
            <span className="text-muted">설명 관점 </span>
            {c.angle}
          </p>
          <div>
            <p className="text-muted">구성안</p>
            <ol className="list-decimal pl-5">
              {c.outline.map((beat, i) => (
                <li key={i} className="break-words">{beat}</li>
              ))}
            </ol>
          </div>
          <div>
            <p className="text-muted">근거 확보 계획</p>
            <ul className="list-disc pl-5">
              {c.evidencePlan.map((plan, i) => (
                <li key={i} className="break-words">
                  {text(SOURCE_TYPE, plan.sourceType)}: {plan.searchQuestion}
                </li>
              ))}
            </ul>
          </div>
          {c.assertions.length > 0 && (
            <div>
              <p className="text-muted">사실 주장(검증 전)</p>
              <ul className="list-disc pl-5">
                {c.assertions.map((claim, i) => (
                  <li key={i} className="break-words">
                    {claim.assertion}
                    <span className="ml-1 text-xs text-muted">{claim.evidenceId ? `근거 ${claim.evidenceId}` : '근거 없음'}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {blocked && (
            <ul className="grid gap-1">
              {candidate.blockCodes.map((code) => (
                <li key={code} className="flex gap-1.5">
                  <XCircle aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
                  <span>{BLOCK[code]?.help ?? code}</span>
                </li>
              ))}
            </ul>
          )}
          {candidate.comparisons.length > 0 && (
            <div>
              <p className="text-muted">비교한 기존 소재</p>
              <ul className="list-disc pl-5">
                {candidate.comparisons.map((comparison, i) => (
                  <li key={i} className="num break-words text-xs">
                    {comparison.topicId ? `소재 #${comparison.topicId}` : '같은 묶음 후보'}
                    {comparison.contentId && ` · 콘텐츠 #${comparison.contentId}`}
                    {comparison.revisionId && ` · revision #${comparison.revisionId}`} — {comparison.reason}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <p className="text-xs break-words text-muted">평가 근거: {candidate.rationale}</p>
        </div>
      </details>
      {canChoose && (
        <div className="flex justify-end">
          <Button variant="primary" size="sm" onClick={onChoose}>
            이 소재 선택
          </Button>
        </div>
      )}
    </li>
  )
}

function StepError({ code }: { code: string | null }) {
  if (!code) {
    return <span className="text-muted">—</span>
  }
  return (
    <span className="break-words">
      {STEP_ERROR[code] ?? code}
      {STEP_ERROR[code] && <span className="num block text-xs text-muted">{code}</span>}
    </span>
  )
}

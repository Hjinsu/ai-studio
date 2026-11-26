import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { AlertTriangle, FileSearch, XCircle } from 'lucide-react'
import {
  getProductionPlanning,
  isTerminal,
  planningKey,
  type PlanningClaim,
  type PlanningFinding,
  type PlanningResearch,
  type PlanningResult,
  type PlanningScenes,
  type PlanningScript,
  type PlanningSource,
  type ProductionRequestDetail,
} from '../api/production'
import { label, text } from '../lib/labels'
import { Card } from '../ui/Card'
import { DateTime } from '../ui/Display'
import { EmptyState, ErrorState, InlineNotice, LoadingSkeleton } from '../ui/States'
import { LabelBadge, StatusBadge } from '../ui/StatusBadge'
import { CLAIM_KIND, FINDING, RELATION, SEVERITY, SOURCE_ORIGIN, SOURCE_STATUS, STAGE } from './ProductionRequestLabels'

const POLL_MS = 5_000
const PLANNING_STAGES = ['RESEARCH', 'SCRIPT', 'SCENES'] as const
const CLAIM_ORDER = ['FACT', 'HYPOTHESIS', 'SCENARIO', 'UNVERIFIED']
type Stage = (typeof PLANNING_STAGES)[number]

/**
 * 제작 상세의 자료 조사·대본·장면 구성(V2-03, 읽기 전용). 적용된 본문과 검수 지적만 보여준다. FAKE 실행과 FIXTURE
 * 자료는 실제 제작·실제 출처 확인과 구분해 표시하며, 편집·재작성 기능은 두지 않는다.
 */
export function PlanningSection({ request }: { request: ProductionRequestDetail }) {
  const terminal = isTerminal(request.status)
  const planning = useQuery({
    // Detail can observe completion before the planning poll sees the last committed results.
    queryKey: [...planningKey(request.id), terminal],
    queryFn: () => getProductionPlanning(request.id),
    refetchInterval: terminal ? false : POLL_MS,
  })
  const title = '자료 조사·대본·장면'
  if (planning.isPending) {
    return (
      <Card title={title}>
        <LoadingSkeleton rows={4} />
      </Card>
    )
  }
  if (planning.isError && planning.error instanceof ApiError && planning.error.status === 404) {
    return (
      <Card title={title}>
        <EmptyState icon={<FileSearch className="size-6" />} {...emptyCopy(request)} />
      </Card>
    )
  }
  if (planning.isError) {
    return (
      <Card title={title}>
        <ErrorState error={planning.error} onRetry={() => void planning.refetch()} />
      </Card>
    )
  }
  const results = planning.data.results
  const byStage = (stage: Stage) => results.find((result) => result.stage === stage)
  const blockedSteps = request.steps.filter(
    (step) => PLANNING_STAGES.includes(step.stage as Stage) && step.lastErrorCode === 'PLANNING_VALIDATION_BLOCKED',
  )
  if (results.length === 0) {
    return (
      <Card title={title}>
        <EmptyState icon={<FileSearch className="size-6" />} {...emptyCopy(request)} />
      </Card>
    )
  }
  const research = byStage('RESEARCH')
  const script = byStage('SCRIPT')
  const scenes = byStage('SCENES')
  return (
    <section aria-label={title} className="grid grid-cols-1 gap-5">
      {planning.data.executionMode === 'FAKE' && (
        <InlineNotice tone="warning" icon={<AlertTriangle className="size-4" />}>
          <p className="font-medium">FAKE 실행 결과입니다. 실제 제작 결과가 아닙니다.</p>
          <p className="mt-0.5">
            출처는 오프라인 FIXTURE 시험 자료이며 실제 문서 조회나 사실 확인을 거치지 않았습니다. 검수는 알려진 규칙과 시험
            사례만 확인하며 의미 검증을 대신하지 않습니다.
          </p>
        </InlineNotice>
      )}
      {blockedSteps.map((step) => (
        <InlineNotice key={step.stage} tone="danger" icon={<XCircle className="size-4" />}>
          {text(STAGE, step.stage)} 검수에서 필수 차단 항목이 확인되어 이후 단계를 실행하지 않았습니다. 자동 재작성은 하지
          않습니다.
        </InlineNotice>
      ))}
      {research && <ResearchCard result={research} />}
      {script && <ScriptCard result={script} />}
      {scenes && <ScenesCard result={scenes} />}
    </section>
  )
}

function emptyCopy(request: ProductionRequestDetail): { title: string; description: string } {
  const research = request.steps.find((step) => step.stage === 'RESEARCH')
  if (research?.status === 'SUCCEEDED') {
    return {
      title: '저장된 조사·대본·장면 본문이 없는 요청입니다',
      description: '이전 방식으로 실행된 요청은 단계 상태만 기록하고 본문을 저장하지 않았습니다.',
    }
  }
  if (research?.status === 'FAILED' || research?.status === 'CANCELLED' || isTerminal(request.status)) {
    return {
      title: '자료 조사 결과가 없습니다',
      description: '자료 조사를 시작하기 전에 요청이 끝났거나, 조사 단계가 결과 없이 종료되었습니다. 진행 단계의 오류를 확인하세요.',
    }
  }
  return {
    title: '자료 조사 전입니다',
    description: '소재가 선정되면 조사·대본·장면 결과와 검수 지적이 여기에 표시됩니다.',
  }
}

function Findings({ findings }: { findings: PlanningFinding[] }) {
  if (findings.length === 0) {
    return <p className="text-sm text-muted">검수 지적 없음</p>
  }
  const ordered = [...findings].sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'BLOCK' ? -1 : 1))
  const blocks = findings.filter((f) => f.severity === 'BLOCK').length
  return (
    <div className="grid gap-2">
      <p className="text-sm text-muted">
        필수 차단 {blocks}건 · 개선 권고 {findings.length - blocks}건
      </p>
      <ul aria-label="검수 지적" className="grid gap-2">
        {ordered.map((finding, index) => (
          <li
            key={`${finding.code}-${finding.location}-${index}`}
            className="grid gap-1 rounded-md border border-line bg-subtle px-3 py-2 text-sm"
          >
            <div className="flex flex-wrap items-center gap-2">
              <LabelBadge value={label(SEVERITY, finding.severity)} />
              <span className="font-medium break-words">{FINDING[finding.code] ?? finding.code}</span>
              <span className="num text-xs break-all text-muted">{finding.location}</span>
            </div>
            <p className="break-words">{finding.correction}</p>
            {finding.references.length > 0 && (
              <p className="num text-xs break-all text-muted">관련: {finding.references.join(', ')}</p>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}

function StageFindings({ result }: { result: PlanningResult }) {
  return (
    <details className="text-sm" open={result.validation.findings.some((f) => f.severity === 'BLOCK')}>
      <summary className="cursor-pointer font-medium">검수 결과</summary>
      <div className="mt-2">
        <Findings findings={result.validation.findings} />
      </div>
    </details>
  )
}

function ResearchCard({ result }: { result: PlanningResult }) {
  const research = result.body as PlanningResearch
  const sources = new Map(research.sources.map((source) => [source.id, source]))
  const used = new Set(research.scriptClaimIds)
  const groups = CLAIM_ORDER.map((kind) => ({ kind, claims: research.claims.filter((claim) => claim.kind === kind) }))
    .filter((group) => group.claims.length > 0)
  return (
    <Card title="자료 조사" description="사실·가설·가상 시나리오를 구분하고 주장별 근거와 한계를 기록합니다.">
      <div className="grid gap-5">
        <dl className="grid gap-2 text-sm">
          <div>
            <dt className="text-muted">핵심 질문</dt>
            <dd className="break-words">{research.question}</dd>
          </div>
          <div>
            <dt className="text-muted">설명 관점</dt>
            <dd className="break-words">{research.angle}</dd>
          </div>
        </dl>
        <StageFindings result={result} />
        {groups.map((group) => (
          <div key={group.kind} className="grid gap-2">
            <h3 className="flex flex-wrap items-center gap-2 text-sm font-semibold">
              <LabelBadge value={label(CLAIM_KIND, group.kind)} />
              <span className="font-normal text-muted">{CLAIM_KIND[group.kind]?.help}</span>
            </h3>
            <ul aria-label={`${CLAIM_KIND[group.kind]?.text ?? group.kind} 주장`} className="grid gap-2">
              {group.claims.map((claim) => (
                <ClaimItem key={claim.id} claim={claim} used={used.has(claim.id)} sources={sources} />
              ))}
            </ul>
          </div>
        ))}
        <div className="grid gap-2">
          <h3 className="text-sm font-semibold">출처 {research.sources.length}개</h3>
          {research.sources.length === 0 ? (
            <p className="text-sm text-muted">보유한 출처가 없습니다.</p>
          ) : (
            <ul aria-label="출처" className="grid gap-2">
              {research.sources.map((source) => (
                <SourceItem key={source.id} source={source} />
              ))}
            </ul>
          )}
        </div>
        {research.investigations.length > 0 && (
          <div className="grid gap-1 text-sm">
            <h3 className="font-semibold">조사 계획</h3>
            <ul className="list-disc pl-5">
              {research.investigations.map((item) => (
                <li key={item.id} className="break-words">
                  {item.question} <span className="num text-xs text-muted">({item.status})</span>
                </li>
              ))}
            </ul>
          </div>
        )}
        {research.limitations.length > 0 && (
          <div className="grid gap-1 text-sm">
            <h3 className="font-semibold">한계</h3>
            <ul className="list-disc pl-5">
              {research.limitations.map((item, index) => (
                <li key={index} className="break-words">{item}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Card>
  )
}

function ClaimItem({ claim, used, sources }: { claim: PlanningClaim; used: boolean; sources: Map<string, PlanningSource> }) {
  return (
    <li className="grid gap-1.5 rounded-md border border-line px-3 py-2 text-sm">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <p className="min-w-0 break-words">
          <span className="num mr-1.5 text-xs text-muted">{claim.id}</span>
          {claim.text}
        </p>
        <StatusBadge tone={used ? 'primary' : 'neutral'}>{used ? '대본 사용' : '대본 제외'}</StatusBadge>
      </div>
      {claim.uncertainty && <p className="break-words text-muted">한계: {claim.uncertainty}</p>}
      {claim.evidenceRefs.length > 0 && (
        <ul aria-label={`${claim.id} 근거`} className="grid gap-1 border-l-2 border-line pl-3">
          {claim.evidenceRefs.map((ref, index) => (
            <li key={`${ref.sourceId}-${index}`} className="grid gap-0.5">
              <span className="flex flex-wrap items-center gap-1.5">
                <LabelBadge value={label(RELATION, ref.relation)} />
                <span className="break-words">{sources.get(ref.sourceId)?.title ?? ref.sourceId}</span>
                <span className="text-xs text-muted">{ref.locator}</span>
              </span>
              <q className="break-words text-muted">{ref.supportingExcerpt}</q>
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}

function SourceItem({ source }: { source: PlanningSource }) {
  // 서버가 조회·확인한 출처만 링크로 연다. FIXTURE·단서 주소는 실제 문서로 오인하지 않도록 글자로만 보여준다.
  const linkable = source.status === 'VERIFIED' && source.url != null && /^https?:\/\//i.test(source.url)
  return (
    <li className="grid gap-1 rounded-md border border-line px-3 py-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <LabelBadge value={label(SOURCE_STATUS, source.status)} />
        <span className="font-medium break-words">{source.title ?? source.id}</span>
      </div>
      <p className="text-xs break-words text-muted">
        {text(SOURCE_ORIGIN, source.origin)}
        {source.publisher && ` · ${source.publisher}`}
      </p>
      {source.url &&
        (linkable ? (
          <a href={source.url} target="_blank" rel="noopener noreferrer" className="text-xs break-all text-primary underline">
            {source.url}
          </a>
        ) : (
          <p className="num text-xs break-all text-muted">{source.url}</p>
        ))}
      <p className="text-xs text-muted">
        확인 시각: {source.checkedAtUtc ? <DateTime value={source.checkedAtUtc} /> : '조회하지 않음'}
      </p>
    </li>
  )
}

function seconds(ms: number): string {
  return `${(ms / 1000).toFixed(1)}초`
}

function ScriptCard({ result }: { result: PlanningResult }) {
  const script = result.body as PlanningScript
  const hook = new Set(script.hookSegmentIds)
  return (
    <Card
      title="대본"
      description={`예상 발화 ${seconds(script.estimatedDurationMs)} · 목표 40~60초 · ${script.language} · 추정 규칙 ${script.timingRuleVersion}`}
    >
      <div className="grid gap-4">
        <p className="text-xs text-muted">예상 발화 시간은 글자 수 기반 추정이며 실제 음성 길이는 음성 생성 단계에서 확인합니다.</p>
        {script.editorialIntent && (
          <div className="text-sm">
            <p className="text-muted">설명 의도</p>
            <p className="break-words">{script.editorialIntent}</p>
          </div>
        )}
        <StageFindings result={result} />
        <ol aria-label="대본 구간" className="grid gap-2">
          {script.segments.map((segment) => (
            <li key={segment.id} className="grid gap-1 rounded-md border border-line px-3 py-2 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="num text-xs text-muted">{segment.id}</span>
                {hook.has(segment.id) && <StatusBadge tone="primary">Hook</StatusBadge>}
                {segment.claimIds.length > 0 ? (
                  <span className="num text-xs text-muted">근거 주장 {segment.claimIds.join(', ')}</span>
                ) : (
                  <span className="text-xs text-muted">연결 주장 없음</span>
                )}
              </div>
              <p className="break-words">{segment.narration}</p>
            </li>
          ))}
        </ol>
      </div>
    </Card>
  )
}

function ScenesCard({ result }: { result: PlanningResult }) {
  const plan = result.body as PlanningScenes
  const scenes = plan.scenes.reduce<{ scene: PlanningScenes['scenes'][number]; from: number; to: number }[]>(
    (acc, scene) => {
      const from = acc.length === 0 ? 0 : acc[acc.length - 1].to
      return [...acc, { scene, from, to: from + scene.estimatedDurationMs }]
    },
    [],
  )
  const start = scenes.length === 0 ? 0 : scenes[scenes.length - 1].to
  return (
    <Card title="장면 구성" description={`장면 ${plan.scenes.length}개 · 합계 ${seconds(start)}`}>
      <div className="grid gap-4">
        <div className="text-sm">
          <p className="text-muted">공통 시각 스타일</p>
          <p className="break-words">{plan.visualStyle}</p>
        </div>
        <StageFindings result={result} />
        <ol aria-label="장면 목록" className="grid gap-3">
          {scenes.map(({ scene, from, to }) => (
            <li key={scene.id} className="grid gap-2 rounded-md border border-line p-3 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-semibold">장면 {scene.order}</span>
                <span className="num text-xs text-muted">
                  {seconds(from)}–{seconds(to)} ({seconds(scene.estimatedDurationMs)}) · 구간 {scene.segmentIds.join(', ')}
                </span>
              </div>
              <dl className="grid gap-1.5 sm:grid-cols-[8rem_1fr] sm:gap-x-3">
                <dt className="text-muted">내레이션</dt>
                <dd className="break-words">{scene.narration}</dd>
                <dt className="text-muted">자막 원문</dt>
                <dd className="break-words">{scene.subtitleText}</dd>
                <dt className="text-muted">화면 구성</dt>
                <dd className="break-words">{scene.visualDescription}</dd>
                <dt className="text-muted">이미지 프롬프트</dt>
                <dd className="break-words">{scene.imagePrompt}</dd>
                <dt className="text-muted">연출</dt>
                <dd className="break-words">{scene.direction}</dd>
                <dt className="text-muted">전환</dt>
                <dd className="break-words">{scene.transition ?? '—'}</dd>
              </dl>
            </li>
          ))}
        </ol>
      </div>
    </Card>
  )
}

import { useState, type FormEvent } from 'react'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { assertSession } from '../api/http'
import { invalidateSessionQueries, useSessionMutation } from '../api/useSessionMutation'
import { Link, useNavigate } from 'react-router'
import { Plus, WalletCards } from 'lucide-react'
import { createPolicy, listPolicies, type BudgetPolicy, type Schemas } from '../api/admin'
import { matchesAmount, sign } from '../lib/decimal'
import { fieldErrors } from '../lib/errors'
import { PERIOD, POLICY_STATUS, label, text } from '../lib/labels'
import { cn } from '../lib/cn'
import { Button, LinkButton } from '../ui/Button'
import { ChannelSelect } from '../ui/ChannelSelect'
import { DataTable, Pagination, type Column } from '../ui/DataTable'
import { Dialog } from '../ui/Dialog'
import { DateTime, Money } from '../ui/Display'
import { FormField, Input, Select, TimeZoneInput } from '../ui/Form'
import { KrwInput } from '../ui/MoneyInput'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, ErrorState, InlineNotice, QueryView } from '../ui/States'
import { LabelBadge } from '../ui/StatusBadge'
import { useToast } from '../ui/toast-context'
import { PolicyTarget, ScopeBadge } from './budget/PolicyBits'

type Scope = Schemas['BudgetScope']
type Period = Schemas['BudgetPeriodType']

const SCOPE_FILTERS: { value: string; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'SYSTEM', label: '시스템 전체' },
  { value: 'CHANNEL', label: '채널별' },
  { value: 'CONTENT_TYPE', label: '콘텐츠 유형' },
]

export function PoliciesPage() {
  const [scope, setScope] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const policies = useQuery({
    queryKey: ['policies', scope, status, page],
    queryFn: () => listPolicies({ scope: scope || undefined, status: status || undefined, page, size: 20 }),
    placeholderData: keepPreviousData,
  })
  const columns: Column<BudgetPolicy>[] = [
    { key: 'scope', header: '범위', cell: (p) => <ScopeBadge scope={p.scope} /> },
    { key: 'target', header: '대상', cell: (p) => <PolicyTarget policy={p} /> },
    { key: 'period', header: '기간', cell: (p) => text(PERIOD, p.periodType) },
    { key: 'limit', header: '한도', align: 'right', cell: (p) => (
      <span className={cn(sign(p.currentRevision.limitKrw) === 0 && 'text-danger')}>
        <Money value={p.currentRevision.limitKrw} />
        {sign(p.currentRevision.limitKrw) === 0 && <span className="ml-1 text-xs">(전면 차단)</span>}
      </span>
    ) },
    { key: 'tz', header: '시간대', cell: (p) => p.currentRevision.timezone, hideBelow: 'lg' },
    { key: 'status', header: '상태', cell: (p) => <LabelBadge value={label(POLICY_STATUS, p.status)} /> },
    { key: 'updated', header: '최근 변경', hideBelow: 'md', cell: (p) => (
      <span className="text-sm">
        <DateTime value={p.updatedAt} showZone={false} /> <span className="text-xs text-muted">r{p.currentRevision.revisionNo}</span>
      </span>
    ) },
    { key: 'actions', header: '작업', align: 'right', cell: (p) => (
      <div className="flex justify-end gap-1.5">
        <LinkButton to={`/budget/policies/${p.id}`} size="sm" variant="ghost">상세</LinkButton>
        <LinkButton to={`/budget/policies/${p.id}?tab=revise`} size="sm">수정</LinkButton>
      </div>
    ) },
  ]
  return (
    <>
      <PageHeader
        title="예산 정책"
        description="기간별 비용 한도를 정합니다. 신규 비용 예약에는 활성 상태의 시스템 전체 일간·월간 정책이 모두 필요합니다."
        actions={<Button variant="primary" icon={<Plus aria-hidden className="size-4" />} onClick={() => setCreating(true)}>정책 만들기</Button>}
      />
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <div role="group" aria-label="범위 필터" className="inline-flex rounded-md border border-line-strong bg-surface p-0.5">
          {SCOPE_FILTERS.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={scope === option.value}
              onClick={() => { setScope(option.value); setPage(0) }}
              className={cn('h-8 rounded px-3 text-sm', scope === option.value ? 'bg-primary-soft font-medium text-primary' : 'text-muted hover:text-text')}
            >
              {option.label}
            </button>
          ))}
        </div>
        <FormField label="상태" className="w-36">
          <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">전체</option>
            <option value="ACTIVE">활성</option>
            <option value="DISABLED">비활성</option>
          </Select>
        </FormField>
      </div>
      <QueryView query={policies}>
        {(data) => data.items.length === 0 ? (
          <EmptyState
            icon={<WalletCards className="size-6" />}
            title={scope || status ? '조건에 맞는 정책이 없습니다' : '예산 정책이 없습니다'}
            description="정책이 없으면 모든 비용 예약이 차단됩니다. 먼저 시스템 전체 일간·월간 정책을 만드세요."
            action={<Button variant="primary" onClick={() => setCreating(true)}>정책 만들기</Button>}
          />
        ) : (
          <>
            <DataTable caption="예산 정책 목록" columns={columns} rows={data.items} rowKey={(p) => p.id} />
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
          </>
        )}
      </QueryView>
      {creating && <CreatePolicyDialog onClose={() => setCreating(false)} />}
    </>
  )
}

const SCOPE_OPTIONS: { value: Scope; title: string; description: string }[] = [
  { value: 'SYSTEM', title: '시스템 전체', description: '모든 채널의 비용 합계에 적용' },
  { value: 'CHANNEL', title: '채널별', description: '선택한 채널 하나에 적용' },
  { value: 'CONTENT_TYPE', title: '콘텐츠 유형', description: '쇼츠·롱폼 유형별(시스템 공통)' },
]

function CreatePolicyDialog({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const toast = useToast()
  const [scope, setScope] = useState<Scope>('SYSTEM')
  const [channelId, setChannelId] = useState('')
  const [contentType, setContentType] = useState<'SHORTS' | 'LONGFORM'>('SHORTS')
  const [periodType, setPeriodType] = useState<Period>('DAY')
  const [limitKrw, setLimitKrw] = useState('')
  const [timezone, setTimezone] = useState('Asia/Seoul')
  const [changeNote, setChangeNote] = useState('')
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({})
  const create = useSessionMutation({
    mutationFn: (body: Schemas['BudgetPolicyCreateRequest']) => createPolicy(body),
    onSuccess: async (policy, _variables, generation) => {
      assertSession(generation)
      toast.success('예산 정책을 만들었습니다.', generation)
      await invalidateSessionQueries(queryClient, generation, ['policies'], ['dashboard'])
      assertSession(generation)
      onClose()
      await navigate(`/budget/policies/${policy.id}`)
    },
  })
  const errors = { ...fieldErrors(create.error), ...localErrors }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (scope === 'CHANNEL' && !channelId) next.channelId = '채널을 선택하세요.'
    if (!matchesAmount(limitKrw, 18, 6)) next.limitKrw = '0 이상의 금액을 입력하세요(소수 최대 6자리, 쉼표 없이).'
    setLocalErrors(next)
    if (Object.keys(next).length > 0) return
    const body: Schemas['BudgetPolicyCreateRequest'] = { scope, periodType, limitKrw, timezone: timezone.trim() }
    if (scope === 'CHANNEL') body.channelId = channelId
    if (scope === 'CONTENT_TYPE') body.contentType = contentType
    if (changeNote.trim()) body.changeNote = changeNote.trim()
    create.mutate(body)
  }
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && !create.isPending && onClose()}
      title="예산 정책 만들기"
      description="같은 범위·대상·기간의 정책은 하나만 만들 수 있습니다. 만든 뒤에는 한도와 시간대만 바꿀 수 있습니다."
      footer={
        <>
          <Button onClick={onClose} disabled={create.isPending}>취소</Button>
          <Button type="submit" form="create-policy-form" variant="primary" loading={create.isPending}>정책 만들기</Button>
        </>
      }
    >
      <form id="create-policy-form" onSubmit={submit} className="grid gap-4" aria-label="정책 만들기">
        <fieldset className="grid gap-2">
          <legend className="mb-1.5 text-sm font-medium">범위</legend>
          <div className="grid gap-2 sm:grid-cols-3">
            {SCOPE_OPTIONS.map((option) => (
              <label
                key={option.value}
                className={cn(
                  'cursor-pointer rounded-md border px-3 py-2.5 text-sm',
                  scope === option.value ? 'border-primary bg-primary-soft' : 'border-line-strong hover:bg-subtle',
                )}
              >
                <input type="radio" name="scope" value={option.value} checked={scope === option.value} onChange={() => setScope(option.value)} className="sr-only" />
                <span className="block font-medium">{option.title}</span>
                <span className="block text-xs text-muted">{option.description}</span>
              </label>
            ))}
          </div>
        </fieldset>
        {scope === 'CHANNEL' && (
          <FormField label="채널" hint="보관된 채널은 선택할 수 없습니다." error={errors.channelId}>
            <ChannelSelect value={channelId} onChange={setChannelId} excludeArchived />
          </FormField>
        )}
        {scope === 'CONTENT_TYPE' && (
          <FormField label="콘텐츠 유형" hint="모든 채널의 해당 유형 콘텐츠에 공통으로 적용됩니다." error={errors.contentType}>
            <Select value={contentType} onChange={(e) => setContentType(e.target.value as 'SHORTS' | 'LONGFORM')}>
              <option value="SHORTS">쇼츠</option>
              <option value="LONGFORM">롱폼</option>
            </Select>
          </FormField>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="기간" hint="콘텐츠당은 콘텐츠 하나의 전체 제작 기간입니다." error={errors.periodType}>
            <Select value={periodType} onChange={(e) => setPeriodType(e.target.value as Period)}>
              <option value="DAY">{PERIOD.DAY}</option>
              <option value="MONTH">{PERIOD.MONTH}</option>
              <option value="LIFETIME">{PERIOD.LIFETIME}</option>
            </Select>
          </FormField>
          <FormField label="예산 시간대" hint="일·월 경계를 정하는 기준" error={errors.timezone}>
            <TimeZoneInput required value={timezone} onChange={(e) => setTimezone(e.target.value)} />
          </FormField>
        </div>
        <FormField label="한도" error={errors.limitKrw}>
          <KrwInput required value={limitKrw} onChange={setLimitKrw} placeholder="예: 300000" />
        </FormField>
        {limitKrw !== '' && matchesAmount(limitKrw, 18, 6) && sign(limitKrw) === 0 && (
          <InlineNotice tone="warning">한도 0원은 무제한이 아니라 <strong>전면 차단</strong>입니다. 이 정책이 적용되는 모든 비용 예약이 거부됩니다.</InlineNotice>
        )}
        <FormField label="변경 메모" optional error={errors.changeNote}>
          <Input maxLength={500} value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
        </FormField>
        {create.error && <ErrorState error={create.error} compact />}
        <p className="text-xs text-muted">
          만든 정책은 <Link to="/budget/usage" className="underline">예산 사용량</Link>에서 기간별로 확인합니다.
        </p>
      </form>
    </Dialog>
  )
}

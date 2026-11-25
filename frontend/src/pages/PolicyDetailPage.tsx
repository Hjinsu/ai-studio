import { useState, type FormEvent, type ReactNode } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router'
import { getPolicy, listBuckets, listPolicyRevisions, revisePolicy, setPolicyEnabled, type BudgetBucket, type BudgetPolicy } from '../api/admin'
import { formatKrw, matchesAmount, sign, trimDecimal } from '../lib/decimal'
import { fieldErrors } from '../lib/errors'
import { PERIOD, POLICY_STATUS, label, text } from '../lib/labels'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { DataTable, Pagination, type Column } from '../ui/DataTable'
import { ConfirmDialog } from '../ui/Dialog'
import { DateTime, Money } from '../ui/Display'
import { FormField, Input, TimeZoneInput } from '../ui/Form'
import { BudgetMeter } from '../ui/Meter'
import { budgetState } from '../lib/budget'
import { KrwInput } from '../ui/MoneyInput'
import { Tabs } from '../ui/Overlay'
import { PageHeader } from '../ui/PageHeader'
import { RevisionHistory } from '../ui/RevisionHistory'
import { EmptyState, ErrorState, InlineNotice, QueryView } from '../ui/States'
import { LabelBadge, StatusBadge } from '../ui/StatusBadge'
import { useToast } from '../ui/toast-context'
import { PolicyTarget, ScopeBadge } from './budget/PolicyBits'

export function PolicyDetailPage() {
  const { policyId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'overview'
  const policy = useQuery({ queryKey: ['policy', policyId], queryFn: () => getPolicy(policyId) })
  return (
    <QueryView query={policy} rows={5}>
      {(data) => (
        <>
          <PageHeader
            title={<>{text(PERIOD, data.periodType)} 예산 · <PolicyTarget policy={data} /></>}
            meta={<><ScopeBadge scope={data.scope} /><LabelBadge value={label(POLICY_STATUS, data.status)} /></>}
            description={`현재 한도 ${formatKrw(data.currentRevision.limitKrw)} · 시간대 ${data.currentRevision.timezone} · r${data.currentRevision.revisionNo}`}
            actions={<StatusToggle policy={data} />}
          />
          <Tabs
            label="예산 정책"
            value={tab}
            onValueChange={(value) => setParams(value === 'overview' ? {} : { tab: value }, { replace: true })}
            items={[
              { value: 'overview', label: '기간별 사용', content: <Buckets policy={data} /> },
              { value: 'revise', label: '한도·시간대 변경', content: <ReviseForm key={data.currentRevision.revisionNo} policy={data} /> },
              { value: 'history', label: '변경 이력', content: <Revisions policy={data} /> },
            ]}
          />
        </>
      )}
    </QueryView>
  )
}

function useRefreshPolicy(policyId: string) {
  const queryClient = useQueryClient()
  return async () => {
    for (const key of [['policy', policyId], ['policy-revisions', policyId], ['policy-buckets', policyId], ['policies'], ['dashboard'], ['usage']]) {
      await queryClient.invalidateQueries({ queryKey: key })
    }
  }
}

function StatusToggle({ policy }: { policy: BudgetPolicy }) {
  const refresh = useRefreshPolicy(policy.id)
  const toast = useToast()
  const [open, setOpen] = useState(false)
  const enable = policy.status !== 'ACTIVE'
  const toggle = useMutation({
    mutationFn: () => setPolicyEnabled(policy.id, enable, policy.version),
    onSuccess: async () => {
      setOpen(false)
      toast.success(enable ? '정책을 활성화했습니다.' : '정책을 비활성화했습니다.')
      await refresh()
    },
  })
  return (
    <>
      <Button variant={enable ? 'secondary' : 'ghost'} onClick={() => { toggle.reset(); setOpen(true) }}>
        {enable ? '활성화' : '비활성화'}
      </Button>
      <ConfirmDialog
        open={open}
        title={enable ? '정책을 활성화할까요?' : '정책을 비활성화할까요?'}
        confirmLabel={enable ? '활성화' : '비활성화'}
        tone={enable ? 'primary' : 'danger'}
        busy={toggle.isPending}
        onConfirm={() => toggle.mutate()}
        onCancel={() => setOpen(false)}
      >
        {enable ? (
          <p>이 정책의 한도가 다시 신규 예약 검사에 적용됩니다.</p>
        ) : policy.scope === 'SYSTEM' ? (
          <p>시스템 전체 정책을 비활성화하면 <strong>모든 신규 비용 예약이 차단</strong>됩니다(시스템 일간·월간 정책 필수).</p>
        ) : (
          <p>비활성화하면 이 정책의 한도가 신규 예약 검사에서 빠집니다. 이미 잡힌 예약과 정산은 그대로입니다.</p>
        )}
        {toggle.error && <ErrorState error={toggle.error} compact />}
      </ConfirmDialog>
    </>
  )
}

function ReviseForm({ policy }: { policy: BudgetPolicy }) {
  const refresh = useRefreshPolicy(policy.id)
  const toast = useToast()
  const [limitKrw, setLimitKrw] = useState(trimDecimal(policy.currentRevision.limitKrw))
  const [timezone, setTimezone] = useState(policy.currentRevision.timezone)
  const [changeNote, setChangeNote] = useState('')
  const [localError, setLocalError] = useState<string | undefined>()
  const revise = useMutation({
    mutationFn: () =>
      revisePolicy(policy.id, {
        baseRevisionNo: policy.currentRevision.revisionNo,
        limitKrw,
        timezone: timezone.trim(),
        ...(changeNote.trim() ? { changeNote: changeNote.trim() } : {}),
      }),
    onSuccess: async (updated) => {
      toast.success(`정책 r${updated.currentRevision.revisionNo}을 저장했습니다.`)
      await refresh()
    },
  })
  const errors = fieldErrors(revise.error)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!matchesAmount(limitKrw, 18, 6)) {
      setLocalError('0 이상의 금액을 입력하세요(소수 최대 6자리, 쉼표 없이).')
      return
    }
    setLocalError(undefined)
    revise.mutate()
  }
  const timezoneChanged = timezone.trim() !== policy.currentRevision.timezone
  return (
    <Card title="한도·시간대 변경" description={`새 정책 버전으로 저장됩니다(기준 r${policy.currentRevision.revisionNo}). 기존 버전은 바뀌지 않습니다.`}>
      <form onSubmit={submit} className="grid max-w-xl gap-4" aria-label="한도·시간대 변경">
        <FormField label="한도" hint="한도 변경은 지금 진행 중인 기간에 바로 적용됩니다." error={localError ?? errors.limitKrw}>
          <KrwInput required value={limitKrw} onChange={setLimitKrw} />
        </FormField>
        {matchesAmount(limitKrw, 18, 6) && sign(limitKrw) === 0 && (
          <InlineNotice tone="warning">한도 0원은 무제한이 아니라 <strong>전면 차단</strong>입니다.</InlineNotice>
        )}
        <FormField label="예산 시간대" error={errors.timezone}>
          <TimeZoneInput required value={timezone} onChange={(e) => setTimezone(e.target.value)} />
        </FormField>
        {timezoneChanged && (
          <InlineNotice tone="primary">
            시간대 변경은 <strong>현재 기간이 끝난 뒤</strong>부터 적용됩니다. 진행 중인 기간의 경계와 이미 잡힌 예약은 그대로입니다.
          </InlineNotice>
        )}
        <FormField label="변경 메모" optional hint="변경 이유를 이력에 남깁니다." error={errors.changeNote}>
          <Input maxLength={500} value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
        </FormField>
        {revise.error && <ErrorState error={revise.error} compact />}
        <div>
          <Button type="submit" variant="primary" loading={revise.isPending}>
            변경 저장
          </Button>
        </div>
      </form>
    </Card>
  )
}

function Revisions({ policy }: { policy: BudgetPolicy }) {
  const [page, setPage] = useState(0)
  const revisions = useQuery({
    queryKey: ['policy-revisions', policy.id, page],
    queryFn: () => listPolicyRevisions(policy.id, { page, size: 50 }),
    placeholderData: keepPreviousData,
  })
  return (
    <QueryView query={revisions}>
      {(data) => (
        <Card title="변경 이력" description="최신이 위에 있습니다. 시간대의 효력 시각은 새 시간대가 실제로 적용되기 시작하는 시점입니다.">
          <RevisionHistory
            label="예산 정책 변경 이력"
            entries={data.items.map((revision, index) => {
              const previous = data.items[index + 1]
              const changes: { field: string; before: ReactNode; after: ReactNode }[] = []
              if (previous && previous.limitKrw !== revision.limitKrw) {
                changes.push({ field: '한도', before: formatKrw(previous.limitKrw), after: formatKrw(revision.limitKrw) })
              }
              if (previous && previous.timezone !== revision.timezone) {
                changes.push({ field: '시간대', before: previous.timezone, after: revision.timezone })
              }
              return {
                key: String(revision.revisionNo),
                title: `r${revision.revisionNo}`,
                current: revision.revisionNo === policy.currentRevision.revisionNo,
                meta: <><DateTime value={revision.createdAt} /> · 관리자 #{revision.createdBy}</>,
                note: revision.changeNote,
                changes: previous ? changes : undefined,
                body: (
                  <p className="mt-1 text-sm text-muted">
                    한도 <Money value={revision.limitKrw} /> · {revision.timezone} (효력 <DateTime value={revision.timezoneEffectiveFrom} timeZone={revision.timezone} />)
                  </p>
                ),
              }
            })}
          />
          <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
        </Card>
      )}
    </QueryView>
  )
}

function Buckets({ policy }: { policy: BudgetPolicy }) {
  const [page, setPage] = useState(0)
  const buckets = useQuery({
    queryKey: ['policy-buckets', policy.id, page],
    queryFn: () => listBuckets(policy.id, { page, size: 20 }),
    placeholderData: keepPreviousData,
  })
  const columns: Column<BudgetBucket>[] = [
    { key: 'period', header: '기간', cell: (b) => <><DateTime value={b.periodStart} timeZone={b.timezone} showZone={false} /> ~ <DateTime value={b.periodEnd} timeZone={b.timezone} /></> },
    { key: 'limit', header: '한도', align: 'right', cell: (b) => <Money value={b.limitKrw} /> },
    { key: 'spent', header: '실제 지출', align: 'right', cell: (b) => <Money value={b.spentKrw} /> },
    { key: 'reserved', header: '예약', align: 'right', cell: (b) => <Money value={b.reservedKrw} /> },
    { key: 'remaining', header: '잔여', align: 'right', cell: (b) => <Money value={b.remainingKrw} className={sign(b.remainingKrw) < 0 ? 'text-danger' : undefined} /> },
    { key: 'state', header: '상태', cell: (b) => <BucketState bucket={b} /> },
  ]
  return (
    <QueryView query={buckets}>
      {(data) => {
        const latest = data.items[0]
        return data.items.length === 0 ? (
          <EmptyState title="아직 기록된 기간이 없습니다" description="이 정책이 적용되는 첫 비용 예약이 생기면 기간별 사용 내역이 만들어집니다." />
        ) : (
          <div className="grid gap-5">
            {page === 0 && latest && (
              <Card title="가장 최근 기간">
                <BudgetMeter
                  title={`${text(PERIOD, policy.periodType)} 사용`}
                  limitKrw={latest.limitKrw}
                  spentKrw={latest.spentKrw}
                  reservedKrw={latest.reservedKrw}
                  remainingKrw={latest.remainingKrw}
                />
              </Card>
            )}
            <DataTable caption="기간별 사용" columns={columns} rows={data.items} rowKey={(b) => b.id ?? b.periodStart} />
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
          </div>
        )
      }}
    </QueryView>
  )
}

function BucketState({ bucket }: { bucket: BudgetBucket }) {
  const state = budgetState(bucket.limitKrw, bucket.spentKrw, bucket.reservedKrw, bucket.remainingKrw)
  const map = { ok: ['정상', 'success'], near: ['소진 임박', 'warning'], exhausted: ['한도 소진', 'danger'], over: ['한도 초과', 'danger'], blocked: ['전면 차단', 'danger'] } as const
  return <StatusBadge tone={map[state][1]}>{map[state][0]}</StatusBadge>
}

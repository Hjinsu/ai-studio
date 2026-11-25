import { useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import {
  getPolicy,
  listBuckets,
  listPolicyRevisions,
  revisePolicy,
  setPolicyEnabled,
  type BudgetBucket,
  type BudgetPolicy,
} from '../api/admin'
import { ConfirmDialog, Empty, ErrorAlert, Field, Pager, QueryView } from '../ui/components'
import { formatInstant, money } from '../ui/format'

export function PolicyDetailPage() {
  const { policyId = '' } = useParams()
  const policy = useQuery({ queryKey: ['policy', policyId], queryFn: () => getPolicy(policyId) })
  return (
    <section aria-labelledby="policy-heading">
      <QueryView query={policy}>
        {(data) => (
          <>
            <h1 id="policy-heading">
              예산 정책 #{data.id} <span className={`badge ${data.status.toLowerCase()}`}>{data.status}</span>
            </h1>
            <dl className="meta">
              <dt>범위</dt>
              <dd>
                {data.scope}
                {data.channelId && <> · 채널 #{data.channelId}</>}
                {data.contentType && <> · {data.contentType}</>}
              </dd>
              <dt>기간</dt>
              <dd>{data.periodType}</dd>
              <dt>현재 한도</dt>
              <dd>{money(data.currentRevision.limitKrw)}</dd>
              <dt>시간대</dt>
              <dd>
                {data.currentRevision.timezone} (효력 {formatInstant(data.currentRevision.timezoneEffectiveFrom, 'UTC')})
              </dd>
              <dt>revision</dt>
              <dd>r{data.currentRevision.revisionNo}</dd>
            </dl>
            <StatusToggle policy={data} />
            <ReviseForm key={data.currentRevision.revisionNo} policy={data} />
            <Revisions policyId={data.id} />
            <Buckets policyId={data.id} />
          </>
        )}
      </QueryView>
    </section>
  )
}

function useRefreshPolicy(policyId: string) {
  const queryClient = useQueryClient()
  return async () => {
    await queryClient.invalidateQueries({ queryKey: ['policy', policyId] })
    await queryClient.invalidateQueries({ queryKey: ['policy-revisions', policyId] })
    await queryClient.invalidateQueries({ queryKey: ['policy-buckets', policyId] })
    await queryClient.invalidateQueries({ queryKey: ['policies'] })
  }
}

function StatusToggle({ policy }: { policy: BudgetPolicy }) {
  const refresh = useRefreshPolicy(policy.id)
  const [open, setOpen] = useState(false)
  const enable = policy.status !== 'ACTIVE'
  const toggle = useMutation({
    mutationFn: () => setPolicyEnabled(policy.id, enable, policy.version),
    onSuccess: async () => {
      setOpen(false)
      await refresh()
    },
  })
  return (
    <>
      <button type="button" className={enable ? undefined : 'danger'} onClick={() => { toggle.reset(); setOpen(true) }}>
        {enable ? '활성화' : '비활성화'}
      </button>
      <ConfirmDialog
        open={open}
        title={enable ? '정책을 활성화할까요?' : '정책을 비활성화할까요?'}
        confirmLabel={enable ? '활성화' : '비활성화'}
        danger={!enable}
        busy={toggle.isPending}
        onConfirm={() => toggle.mutate()}
        onCancel={() => setOpen(false)}
      >
        {!enable && policy.scope === 'SYSTEM' && (
          <p>SYSTEM 정책을 비활성화하면 모든 신규 지출이 차단됩니다.</p>
        )}
        <ErrorAlert error={toggle.error} />
      </ConfirmDialog>
    </>
  )
}

function ReviseForm({ policy }: { policy: BudgetPolicy }) {
  const refresh = useRefreshPolicy(policy.id)
  const [limitKrw, setLimitKrw] = useState(policy.currentRevision.limitKrw)
  const [timezone, setTimezone] = useState(policy.currentRevision.timezone)
  const [changeNote, setChangeNote] = useState('')
  const revise = useMutation({
    mutationFn: () =>
      revisePolicy(policy.id, {
        baseRevisionNo: policy.currentRevision.revisionNo,
        limitKrw,
        timezone,
        ...(changeNote ? { changeNote } : {}),
      }),
    onSuccess: refresh,
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    revise.mutate()
  }
  return (
    <form onSubmit={submit} className="card" aria-labelledby="revise-heading">
      <h2 id="revise-heading">한도·시간대 변경 (새 revision)</h2>
      <p className="muted">한도 변경은 열린 기간에 즉시 적용됩니다. 시간대 변경은 현재 기간이 끝난 뒤부터 적용됩니다.</p>
      <Field label="한도(KRW)">
        <input required inputMode="decimal" pattern="(0|[1-9][0-9]{0,17})(\.[0-9]{1,6})?" value={limitKrw}
          onChange={(e) => setLimitKrw(e.target.value)} />
      </Field>
      <Field label="시간대(IANA)">
        <input required maxLength={64} value={timezone} onChange={(e) => setTimezone(e.target.value)} />
      </Field>
      <Field label="변경 메모">
        <input maxLength={500} value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
      </Field>
      <button type="submit" className="primary" disabled={revise.isPending}>revision 생성</button>
      <ErrorAlert error={revise.error} />
    </form>
  )
}

function Revisions({ policyId }: { policyId: string }) {
  const [page, setPage] = useState(0)
  const revisions = useQuery({
    queryKey: ['policy-revisions', policyId, page],
    queryFn: () => listPolicyRevisions(policyId, { page, size: 10 }),
    placeholderData: keepPreviousData,
  })
  return (
    <>
      <h2>revision 이력</h2>
      <QueryView query={revisions}>
        {(data) => (
          <>
            <table>
              <thead>
                <tr>
                  <th scope="col">revision</th>
                  <th scope="col">한도</th>
                  <th scope="col">시간대(효력 시각 UTC)</th>
                  <th scope="col">메모</th>
                  <th scope="col">작성</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((revision) => (
                  <tr key={revision.revisionNo}>
                    <td>r{revision.revisionNo}</td>
                    <td>{money(revision.limitKrw)}</td>
                    <td>
                      {revision.timezone} ({formatInstant(revision.timezoneEffectiveFrom, 'UTC')})
                    </td>
                    <td>{revision.changeNote ?? '—'}</td>
                    <td>
                      #{revision.createdBy} · {formatInstant(revision.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
          </>
        )}
      </QueryView>
    </>
  )
}

function Buckets({ policyId }: { policyId: string }) {
  const [page, setPage] = useState(0)
  const buckets = useQuery({
    queryKey: ['policy-buckets', policyId, page],
    queryFn: () => listBuckets(policyId, { page, size: 10 }),
    placeholderData: keepPreviousData,
  })
  return (
    <>
      <h2>기간별 bucket</h2>
      <QueryView query={buckets}>
        {(data) =>
          data.items.length === 0 ? (
            <Empty>아직 예약이 없어 생성된 기간이 없습니다.</Empty>
          ) : (
            <>
              <BucketRows buckets={data.items} />
              <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
            </>
          )
        }
      </QueryView>
    </>
  )
}

function BucketRows({ buckets }: { buckets: BudgetBucket[] }) {
  return (
    <table>
      <thead>
        <tr>
          <th scope="col">기간(bucket 시간대)</th>
          <th scope="col">한도</th>
          <th scope="col">실제 지출</th>
          <th scope="col">예약</th>
          <th scope="col">잔여</th>
        </tr>
      </thead>
      <tbody>
        {buckets.map((bucket) => (
          <tr key={bucket.periodStart}>
            <td>
              {formatInstant(bucket.periodStart, bucket.timezone)} ~ {formatInstant(bucket.periodEnd, bucket.timezone)}
            </td>
            <td>{money(bucket.limitKrw)}</td>
            <td>{money(bucket.spentKrw)}</td>
            <td>{money(bucket.reservedKrw)}</td>
            <td className={bucket.remainingKrw.startsWith('-') ? 'warn' : undefined}>{money(bucket.remainingKrw)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

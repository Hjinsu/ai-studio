import { useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { createPolicy, listPolicies, type Schemas } from '../api/admin'
import { Empty, ErrorAlert, Field, Pager, QueryView } from '../ui/components'
import { formatInstant, money } from '../ui/format'

type Scope = Schemas['BudgetScope']
type PeriodType = Schemas['BudgetPeriodType']

export function PoliciesPage() {
  const [scope, setScope] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const policies = useQuery({
    queryKey: ['policies', scope, status, page],
    queryFn: () => listPolicies({ scope: scope || undefined, status: status || undefined, page, size: 20 }),
    placeholderData: keepPreviousData,
  })
  return (
    <section aria-labelledby="policies-heading">
      <h1 id="policies-heading">예산 정책</h1>
      <p className="muted">
        신규 지출에는 활성 SYSTEM DAY·MONTH 정책이 모두 필요합니다(없으면 차단). 한도 0원은 무제한이 아니라 전면
        차단입니다.
      </p>
      <div className="filters">
        <Field label="범위">
          <select value={scope} onChange={(e) => { setScope(e.target.value); setPage(0) }}>
            <option value="">전체</option>
            <option value="SYSTEM">SYSTEM</option>
            <option value="CHANNEL">CHANNEL</option>
            <option value="CONTENT_TYPE">CONTENT_TYPE</option>
          </select>
        </Field>
        <Field label="상태">
          <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">전체</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="DISABLED">DISABLED</option>
          </select>
        </Field>
      </div>
      <QueryView query={policies}>
        {(data) =>
          data.items.length === 0 ? (
            <Empty>정책이 없습니다.</Empty>
          ) : (
            <>
              <table>
                <thead>
                  <tr>
                    <th scope="col">ID</th>
                    <th scope="col">범위</th>
                    <th scope="col">기간</th>
                    <th scope="col">한도</th>
                    <th scope="col">시간대</th>
                    <th scope="col">상태</th>
                    <th scope="col">revision</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((policy) => (
                    <tr key={policy.id}>
                      <td>
                        <Link to={`/budget/policies/${policy.id}`}>#{policy.id}</Link>
                      </td>
                      <td>
                        {policy.scope}
                        {policy.channelId && <> · 채널 #{policy.channelId}</>}
                        {policy.contentType && <> · {policy.contentType}</>}
                      </td>
                      <td>{policy.periodType}</td>
                      <td>{money(policy.currentRevision.limitKrw)}</td>
                      <td>{policy.currentRevision.timezone}</td>
                      <td>{policy.status}</td>
                      <td>
                        r{policy.currentRevision.revisionNo} · {formatInstant(policy.updatedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
            </>
          )
        }
      </QueryView>
      <CreatePolicyForm />
    </section>
  )
}

function CreatePolicyForm() {
  const queryClient = useQueryClient()
  const [scope, setScope] = useState<Scope>('SYSTEM')
  const [channelId, setChannelId] = useState('')
  const [contentType, setContentType] = useState<'SHORTS' | 'LONGFORM'>('SHORTS')
  const [periodType, setPeriodType] = useState<PeriodType>('DAY')
  const [limitKrw, setLimitKrw] = useState('')
  const [timezone, setTimezone] = useState('Asia/Seoul')
  const [changeNote, setChangeNote] = useState('')
  const create = useMutation({
    mutationFn: () => {
      const body: Schemas['BudgetPolicyCreateRequest'] = { scope, periodType, limitKrw, timezone }
      if (scope === 'CHANNEL') body.channelId = channelId
      if (scope === 'CONTENT_TYPE') body.contentType = contentType
      if (changeNote) body.changeNote = changeNote
      return createPolicy(body)
    },
    onSuccess: async () => {
      setLimitKrw('')
      await queryClient.invalidateQueries({ queryKey: ['policies'] })
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    create.mutate()
  }
  return (
    <form onSubmit={submit} className="card" aria-labelledby="create-policy-heading">
      <h2 id="create-policy-heading">정책 생성</h2>
      <Field label="범위">
        <select value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
          <option value="SYSTEM">SYSTEM</option>
          <option value="CHANNEL">CHANNEL</option>
          <option value="CONTENT_TYPE">CONTENT_TYPE</option>
        </select>
      </Field>
      {scope === 'CHANNEL' && (
        <Field label="채널 ID">
          <input required pattern="[1-9][0-9]{0,18}" value={channelId} onChange={(e) => setChannelId(e.target.value)} />
        </Field>
      )}
      {scope === 'CONTENT_TYPE' && (
        <Field label="콘텐츠 유형">
          <select value={contentType} onChange={(e) => setContentType(e.target.value as 'SHORTS' | 'LONGFORM')}>
            <option value="SHORTS">SHORTS</option>
            <option value="LONGFORM">LONGFORM</option>
          </select>
        </Field>
      )}
      <Field label="기간">
        <select value={periodType} onChange={(e) => setPeriodType(e.target.value as PeriodType)}>
          <option value="DAY">DAY</option>
          <option value="MONTH">MONTH</option>
          <option value="LIFETIME">LIFETIME</option>
        </select>
      </Field>
      <Field label="한도(KRW)" hint="소수 최대 6자리. 0은 전면 차단">
        <input required inputMode="decimal" pattern="(0|[1-9][0-9]{0,17})(\.[0-9]{1,6})?" value={limitKrw}
          onChange={(e) => setLimitKrw(e.target.value)} />
      </Field>
      <Field label="예산 시간대(IANA)">
        <input required maxLength={64} value={timezone} onChange={(e) => setTimezone(e.target.value)} />
      </Field>
      <Field label="변경 메모">
        <input maxLength={500} value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
      </Field>
      <button type="submit" className="primary" disabled={create.isPending}>생성</button>
      <ErrorAlert error={create.error} />
    </form>
  )
}

import { useState, type FormEvent } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listCostLedger } from '../api/admin'
import { Field, Pager, QueryView } from '../ui/components'
import { LedgerTable } from './LedgerTable'

interface Filters {
  channelId: string
  reservationId: string
  requestKey: string
  entryType: string
}

const EMPTY: Filters = { channelId: '', reservationId: '', requestKey: '', entryType: '' }

export function CostLedgerPage() {
  const [draft, setDraft] = useState<Filters>(EMPTY)
  const [filters, setFilters] = useState<Filters>(EMPTY)
  const [page, setPage] = useState(0)
  const ledger = useQuery({
    queryKey: ['ledger', filters, page],
    queryFn: () =>
      listCostLedger({
        channelId: filters.channelId || undefined,
        reservationId: filters.reservationId || undefined,
        requestKey: filters.requestKey || undefined,
        entryType: filters.entryType || undefined,
        page,
        size: 50,
      }),
    placeholderData: keepPreviousData,
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setPage(0)
    setFilters(draft)
  }
  const set = (key: keyof Filters) => (value: string) => setDraft((current) => ({ ...current, [key]: value }))
  return (
    <section aria-labelledby="ledger-heading">
      <h1 id="ledger-heading">비용 원장</h1>
      <p className="muted">원장은 추가만 가능하며 수정·삭제할 수 없습니다. 정정은 조정·환불 이벤트로 기록됩니다.</p>
      <form onSubmit={submit} className="filters">
        <Field label="채널 ID">
          <input pattern="[1-9][0-9]{0,18}" value={draft.channelId} onChange={(e) => set('channelId')(e.target.value)} />
        </Field>
        <Field label="예약 ID">
          <input pattern="[1-9][0-9]{0,18}" value={draft.reservationId}
            onChange={(e) => set('reservationId')(e.target.value)} />
        </Field>
        <Field label="요청 키">
          <input maxLength={128} value={draft.requestKey} onChange={(e) => set('requestKey')(e.target.value)} />
        </Field>
        <Field label="유형">
          <select value={draft.entryType} onChange={(e) => set('entryType')(e.target.value)}>
            <option value="">전체</option>
            {['RESERVE', 'PROVISIONAL', 'CONFIRM', 'RELEASE', 'ADJUSTMENT', 'REFUND'].map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </Field>
        <button type="submit">조회</button>
      </form>
      <QueryView query={ledger}>
        {(data) => (
          <>
            <p className="muted">총 {data.totalElements}건</p>
            <LedgerTable entries={data.items} />
            <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
          </>
        )}
      </QueryView>
    </section>
  )
}

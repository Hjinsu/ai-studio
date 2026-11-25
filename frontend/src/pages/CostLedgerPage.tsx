import { useState, type FormEvent } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { BookOpenText, Search } from 'lucide-react'
import { listCostLedger } from '../api/admin'
import { ENTRY_TYPE } from '../lib/labels'
import { Button } from '../ui/Button'
import { ChannelSelect } from '../ui/ChannelSelect'
import { Pagination } from '../ui/DataTable'
import { FormField, Input, Select } from '../ui/Form'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, QueryView } from '../ui/States'
import { LedgerTable } from './LedgerTable'

interface Filters {
  channelId: string
  reservationId: string
  requestKey: string
  entryType: string
}

const EMPTY: Filters = { channelId: '', reservationId: '', requestKey: '', entryType: '' }

/** 비용 원장 조회(추가 전용, 수정·삭제 없음). 정정은 조정·환불 이벤트로만 기록된다. */
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
    setFilters({ ...draft, reservationId: draft.reservationId.trim(), requestKey: draft.requestKey.trim() })
  }
  const filtered = Object.values(filters).some(Boolean)
  return (
    <>
      <PageHeader title="비용 원장" description="모든 비용 변동의 불변 기록입니다. 잘못된 기록은 수정하지 않고 조정·환불 이벤트로 바로잡습니다." />
      <form onSubmit={submit} className="mb-4 grid gap-3 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_8rem_10rem_auto] lg:items-end" aria-label="원장 검색">
        <FormField label="채널">
          <ChannelSelect value={draft.channelId} onChange={(channelId) => setDraft({ ...draft, channelId })} allLabel="전체 채널" />
        </FormField>
        <FormField label="요청 키">
          <Input maxLength={128} value={draft.requestKey} onChange={(e) => setDraft({ ...draft, requestKey: e.target.value })} />
        </FormField>
        <FormField label="예약 번호">
          <Input inputMode="numeric" pattern="[1-9][0-9]{0,18}" value={draft.reservationId} onChange={(e) => setDraft({ ...draft, reservationId: e.target.value })} />
        </FormField>
        <FormField label="유형">
          <Select value={draft.entryType} onChange={(e) => setDraft({ ...draft, entryType: e.target.value })}>
            <option value="">전체</option>
            {Object.entries(ENTRY_TYPE).map(([value, entry]) => <option key={value} value={value}>{entry.text}</option>)}
          </Select>
        </FormField>
        <Button type="submit" icon={<Search aria-hidden className="size-4" />} loading={ledger.isFetching && !ledger.isPending}>
          조회
        </Button>
      </form>
      <QueryView query={ledger} rows={6}>
        {(data) => data.items.length === 0 ? (
          <EmptyState icon={<BookOpenText className="size-6" />} title={filtered ? '조건에 맞는 기록이 없습니다' : '원장 기록이 없습니다'} description={filtered ? '검색 조건을 바꿔 보세요.' : '비용 예약이 생기면 기록이 쌓입니다.'} />
        ) : (
          <>
            <LedgerTable entries={data.items} />
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
          </>
        )}
      </QueryView>
    </>
  )
}

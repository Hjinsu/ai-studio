import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { Receipt } from 'lucide-react'
import { dashboardSummary, listReservations, type Reservation } from '../api/admin'
import { useChannelName } from '../api/useChannels'
import { RESERVATION_STATUS, label } from '../lib/labels'
import { cn } from '../lib/cn'
import { DataTable, Pagination, type Column } from '../ui/DataTable'
import { DateTime, Money } from '../ui/Display'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, InlineNotice, QueryView } from '../ui/States'
import { LabelBadge } from '../ui/StatusBadge'

const FILTERS = ['', 'RECONCILIATION_REQUIRED', 'ACTIVE', 'SETTLED', 'RELEASED']

/** 비용 예약 목록. 정산 확인 필요(외부 결과 불명)는 자동 해제되지 않으며 관리자가 확인해야 한다. */
export function ReservationsPage() {
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const [page, setPage] = useState(0)
  const channelName = useChannelName()
  const reservations = useQuery({
    queryKey: ['reservations', status, page],
    queryFn: () => listReservations({ status: status || undefined, page, size: 20 }),
    placeholderData: keepPreviousData,
  })
  const summary = useQuery({ queryKey: ['dashboard'], queryFn: dashboardSummary })
  const pending = summary.data?.reservationsRequiringReconciliation ?? 0
  const columns: Column<Reservation>[] = [
    { key: 'id', header: '예약', cell: (r) => (
      <Link to={`/budget/reservations/${r.id}`} className="font-medium hover:text-primary hover:underline">
        #{r.id}
        <span className="block max-w-48 truncate text-xs font-normal text-muted" title={r.requestKey}>{r.requestKey}</span>
      </Link>
    ) },
    { key: 'purpose', header: '용도·채널', cell: (r) => <>{r.purpose}<span className="block text-xs text-muted">{r.channelId ? channelName(r.channelId) : '채널 없음'}</span></> },
    { key: 'status', header: '상태', cell: (r) => <LabelBadge value={label(RESERVATION_STATUS, r.status)} /> },
    { key: 'estimate', header: '예상 비용', align: 'right', cell: (r) => <><Money value={r.estimateKrw} />{r.originalCurrency !== 'KRW' && <span className="block text-xs text-muted"><Money value={r.estimateOriginal} currency={r.originalCurrency} /></span>}</> },
    { key: 'reserved', header: '남은 예약', align: 'right', cell: (r) => <Money value={r.reservedRemainingKrw} /> },
    { key: 'cost', header: '인식 비용', align: 'right', cell: (r) => <Money value={r.recognizedCostKrw} /> },
    { key: 'created', header: '생성', hideBelow: 'md', cell: (r) => <DateTime value={r.createdAt} showZone={false} /> },
  ]
  return (
    <>
      <PageHeader title="예약·정산" description="비용 예약과 정산 상태를 확인합니다. 결과가 불명확한 예약은 관리자가 과금 여부를 확인해 정산합니다." />
      {pending > 0 && status !== 'RECONCILIATION_REQUIRED' && (
        <div className="mb-4">
          <InlineNotice tone="danger">
            정산 확인이 필요한 예약이 <strong>{pending}건</strong> 있습니다.{' '}
            <button type="button" className="font-medium underline" onClick={() => { setPage(0); setParams({ status: 'RECONCILIATION_REQUIRED' }) }}>
              확인 필요 목록 보기
            </button>
          </InlineNotice>
        </div>
      )}
      <div role="group" aria-label="상태 필터" className="mb-3 flex flex-wrap gap-1.5">
        {FILTERS.map((value) => (
          <button
            key={value || 'all'}
            type="button"
            aria-pressed={status === value}
            onClick={() => { setPage(0); setParams(value ? { status: value } : {}) }}
            className={cn(
              'h-8 rounded-full border px-3 text-sm',
              status === value ? 'border-primary bg-primary-soft font-medium text-primary' : 'border-line-strong text-muted hover:text-text',
            )}
          >
            {value ? label(RESERVATION_STATUS, value).text : '전체'}
            {value === 'RECONCILIATION_REQUIRED' && pending > 0 && <span className="num ml-1">{pending}</span>}
          </button>
        ))}
      </div>
      <QueryView query={reservations}>
        {(data) => data.items.length === 0 ? (
          <EmptyState icon={<Receipt className="size-6" />} title="예약이 없습니다" description={status ? '다른 상태를 선택해 보세요.' : '비용 예약은 제작 작업이 provider를 호출하기 전에 자동으로 만들어집니다.'} />
        ) : (
          <>
            <DataTable
              caption="비용 예약 목록"
              columns={columns}
              rows={data.items}
              rowKey={(r) => r.id}
              rowTone={(r) => (r.status === 'RECONCILIATION_REQUIRED' ? 'danger' : undefined)}
            />
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
          </>
        )}
      </QueryView>
    </>
  )
}

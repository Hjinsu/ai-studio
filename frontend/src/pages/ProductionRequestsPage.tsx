import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Clapperboard } from 'lucide-react'
import { listProductionRequests, productionListKey, type ProductionRequestListItem } from '../api/production'
import { LinkButton } from '../ui/Button'
import { DataTable, Pagination, type Column } from '../ui/DataTable'
import { DateTime, Money } from '../ui/Display'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, InlineNotice, QueryView } from '../ui/States'
import { LabelBadge } from '../ui/StatusBadge'
import { FAKE_RUN_NOTICE, productionStatusLabel } from './ProductionRequestLabels'

/** 본인 제작 요청 이력(최신순, 서버 정렬 유지). 상세 화면에서 단계·후보·비용을 확인한다. */
export function ProductionRequestsPage() {
  const [page, setPage] = useState(0)
  const requests = useQuery({
    queryKey: productionListKey(page),
    queryFn: () => listProductionRequests(page),
    placeholderData: keepPreviousData,
  })
  const columns: Column<ProductionRequestListItem>[] = [
    {
      key: 'id',
      header: '요청',
      cell: (r) => (
        <Link to={`/production/${r.id}`} className="num font-medium hover:text-primary hover:underline">
          #{r.id}
        </Link>
      ),
    },
    { key: 'channel', header: '채널', cell: (r) => r.channelName },
    {
      key: 'title',
      header: '선정 소재',
      cell: (r) =>
        r.selectedTitle ? (
          <span className="block max-w-72 break-words">{r.selectedTitle}</span>
        ) : (
          <span className="text-muted">미선정</span>
        ),
    },
    { key: 'status', header: '상태', cell: (r) => <LabelBadge value={productionStatusLabel(r.status)} /> },
    {
      key: 'cost',
      header: '확정 비용',
      align: 'right',
      cell: (r) => (
        <>
          <Money value={r.recognizedKrw} />
          <span className="block text-xs text-muted">
            예약 <Money value={r.reservedKrw} /> · 한도 <Money value={r.limitKrw} />
          </span>
        </>
      ),
    },
    { key: 'created', header: '생성', hideBelow: 'md', cell: (r) => <DateTime value={r.createdAt} showZone={false} /> },
  ]
  return (
    <>
      <PageHeader
        title="제작 이력"
        description="내가 만든 제작 요청을 최신순으로 보여줍니다. 요청을 열면 단계·소재 후보·비용을 확인할 수 있습니다."
        actions={<LinkButton to="/production" variant="primary">제작 시작</LinkButton>}
      />
      <div className="mb-4">
        <InlineNotice tone="warning">{FAKE_RUN_NOTICE}</InlineNotice>
      </div>
      <QueryView query={requests}>
        {(data) =>
          data.items.length === 0 ? (
            <EmptyState
              icon={<Clapperboard className="size-6" />}
              title="제작 요청이 없습니다"
              description="제작 시작 화면에서 채널과 유형을 고르면 요청이 만들어집니다."
            />
          ) : (
            <>
              <DataTable
                caption="제작 요청 목록"
                columns={columns}
                rows={data.items}
                rowKey={(r) => r.id}
                rowTone={(r) => (r.status === 'FAILED' ? 'danger' : r.status === 'RECOVERY_REQUIRED' ? 'warning' : undefined)}
              />
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
            </>
          )
        }
      </QueryView>
    </>
  )
}

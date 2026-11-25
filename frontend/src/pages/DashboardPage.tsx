import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { dashboardSummary } from '../api/admin'
import { QueryView } from '../ui/components'
import { formatInstant } from '../ui/format'
import { BucketTable } from './BucketTable'

/** 대시보드(ADR-052): SYSTEM 예산만 집계하며 CHANNEL 예산을 더하지 않는다. */
export function DashboardPage() {
  const summary = useQuery({ queryKey: ['dashboard'], queryFn: dashboardSummary })
  return (
    <section aria-labelledby="dashboard-heading">
      <h1 id="dashboard-heading">대시보드</h1>
      <QueryView query={summary}>
        {(data) => {
          return (
            <>
              <div className="stats">
                <div className="stat">
                  <span>지출</span>
                  <strong className={data.spendingEnabled ? 'ok' : 'warn'}>
                    {data.spendingEnabled ? '허용' : '차단'}
                  </strong>
                  <Link to="/budget/control">지출 제어</Link>
                </div>
                <div className="stat">
                  <span>정합성</span>
                  <strong className={data.consistency.status === 'ERROR' ? 'warn' : 'ok'}>
                    {data.consistency.status}
                  </strong>
                  <small>
                    이슈 {data.consistency.issueCount}건 · {formatInstant(data.consistency.checkedAt)}
                  </small>
                </div>
                <div className="stat">
                  <span>정산 확인 필요</span>
                  <strong className={data.reservationsRequiringReconciliation > 0 ? 'warn' : 'ok'}>
                    {data.reservationsRequiringReconciliation}건
                  </strong>
                  <Link to="/budget/reservations?status=RECONCILIATION_REQUIRED">확인</Link>
                </div>
                <div className="stat">
                  <span>채널</span>
                  <strong>{data.channelCounts.active}</strong>
                  <small>
                    활성 {data.channelCounts.active} · 일시정지 {data.channelCounts.paused} · 보관{' '}
                    {data.channelCounts.archived}
                  </small>
                </div>
              </div>
              <h2>시스템 예산 (현재 기간)</h2>
              <BucketTable buckets={data.systemUsage} empty="SYSTEM 예산 정책이 없습니다. 지출 전에 DAY·MONTH 정책이 필요합니다." />
              <p className="muted">생성 시각 {formatInstant(data.generatedAt)}</p>
            </>
          )
        }}
      </QueryView>
    </section>
  )
}

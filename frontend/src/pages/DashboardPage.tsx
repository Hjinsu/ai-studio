import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { CircleAlert, CircleCheck, ShieldAlert, ShieldCheck, Tv, WalletCards } from 'lucide-react'
import { dashboardSummary, type BudgetBucket } from '../api/admin'
import { CONSISTENCY, label } from '../lib/labels'
import { percentOf } from '../lib/decimal'
import { spendingView } from '../lib/budget'
import { Card, StatCard } from '../ui/Card'
import { DateTime, Money } from '../ui/Display'
import { BudgetMeter } from '../ui/Meter'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, QueryView } from '../ui/States'
import { LinkButton } from '../ui/Button'

function systemBucket(buckets: BudgetBucket[], periodType: 'DAY' | 'MONTH'): BudgetBucket | undefined {
  return buckets.find((bucket) => bucket.scope === 'SYSTEM' && bucket.periodType === periodType)
}

function usage(bucket: BudgetBucket | undefined): string | undefined {
  if (!bucket) return undefined
  const pct = percentOf(bucket.spentKrw, bucket.limitKrw)
  return pct === null ? '한도 0원(전면 차단)' : `실제 지출 ${pct.toFixed(1)}%`
}

/**
 * 대시보드(ADR-052). 예산 수치는 SYSTEM 정책 bucket만 쓴다 — SYSTEM 지출에 모든 채널 비용이 이미 포함되므로
 * CHANNEL 예산을 더하지 않는다(중복 합산 금지). 데이터가 없으면 없다고 표시한다.
 */
export function DashboardPage() {
  const summary = useQuery({ queryKey: ['dashboard'], queryFn: dashboardSummary })
  return (
    <>
      <PageHeader title="대시보드" description="비용 지출 상태와 운영 현황을 확인합니다. 금액은 시스템 전체 예산 기준입니다." />
      <QueryView query={summary} rows={6}>
        {(data) => {
          const day = systemBucket(data.systemUsage, 'DAY')
          const month = systemBucket(data.systemUsage, 'MONTH')
          const consistency = label(CONSISTENCY, data.consistency.status)
          const spending = spendingView(data.spendingEnabled, data.consistency.status)
          return (
            <div className="grid gap-6">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <StatCard
                  label="신규 지출"
                  icon={spending.blocked ? <ShieldAlert aria-hidden className="size-4" /> : <ShieldCheck aria-hidden className="size-4" />}
                  value={spending.blocked ? '차단' : '관리자 허용'}
                  tone={spending.blocked ? (spending.consistencyError ? 'danger' : 'warning') : 'success'}
                  detail={
                    <>
                      {!spending.adminEnabled ? '관리자 차단' : spending.consistencyError ? '정합성 오류로 차단(관리자 설정은 허용)' : '나머지 조건은 예약 시 서버가 검사'}
                      {' · '}
                      <Link to="/budget/control" className="text-primary underline-offset-2 hover:underline">지출 제어</Link>
                    </>
                  }
                />
                <StatCard
                  label="정합성"
                  icon={data.consistency.status === 'ERROR' ? <CircleAlert aria-hidden className="size-4" /> : <CircleCheck aria-hidden className="size-4" />}
                  value={consistency.text}
                  tone={consistency.tone === 'danger' ? 'danger' : consistency.tone === 'success' ? 'success' : undefined}
                  detail={
                    data.consistency.checkedAt ? (
                      <>
                        오류 {data.consistency.issueCount}건 · <DateTime value={data.consistency.checkedAt} showZone={false} />
                      </>
                    ) : (
                      '아직 검사하지 않았습니다'
                    )
                  }
                />
                <StatCard
                  label="정산 확인 필요"
                  icon={<CircleAlert aria-hidden className="size-4" />}
                  value={`${data.reservationsRequiringReconciliation}건`}
                  tone={data.reservationsRequiringReconciliation > 0 ? 'danger' : undefined}
                  detail={<Link to="/budget/reservations?status=RECONCILIATION_REQUIRED" className="text-primary underline-offset-2 hover:underline">정산 확인 목록</Link>}
                />
                <StatCard
                  label="운영 채널"
                  icon={<Tv aria-hidden className="size-4" />}
                  value={`${data.channelCounts.active}개`}
                  detail={`일시정지 ${data.channelCounts.paused} · 보관 ${data.channelCounts.archived}`}
                />
              </div>

              {!day && !month ? (
                <EmptyState
                  icon={<WalletCards className="size-6" />}
                  title="시스템 예산 정책이 없습니다"
                  description="시스템 전체 일간·월간 정책이 모두 활성이어야 비용 예약이 가능합니다. 정책이 없으면 모든 지출이 차단됩니다."
                  action={
                    <LinkButton to="/budget/policies" variant="primary">
                      예산 정책 만들기
                    </LinkButton>
                  }
                />
              ) : (
                <>
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                    <StatCard label="오늘의 예산" value={day ? <Money value={day.limitKrw} /> : '정책 없음'} detail={usage(day)} />
                    <StatCard label="이번 달 예산" value={month ? <Money value={month.limitKrw} /> : '정책 없음'} detail={usage(month)} />
                    <StatCard
                      label="실제 지출(이번 달)"
                      value={month ? <Money value={month.spentKrw} /> : '—'}
                      detail={day ? <>오늘 <Money value={day.spentKrw} /></> : undefined}
                    />
                    <StatCard
                      label="예약 금액(이번 달)"
                      value={month ? <Money value={month.reservedKrw} /> : '—'}
                      detail={day ? <>오늘 <Money value={day.reservedKrw} /></> : undefined}
                    />
                  </div>
                  <Card
                    title="예산 사용률"
                    description="실제 지출과 예약(아직 확정되지 않은 금액)을 나눠 표시합니다. 신규 예약 가능 여부는 서버가 최종 판단합니다."
                  >
                    <div className="grid gap-8 lg:grid-cols-2">
                      {day ? (
                        <BudgetMeter title="오늘(일간)" {...meterProps(day)} caption={periodCaption(day)} />
                      ) : (
                        <p className="text-sm text-muted">시스템 일간 정책이 없습니다. 지출이 차단됩니다.</p>
                      )}
                      {month ? (
                        <BudgetMeter title="이번 달(월간)" {...meterProps(month)} caption={periodCaption(month)} />
                      ) : (
                        <p className="text-sm text-muted">시스템 월간 정책이 없습니다. 지출이 차단됩니다.</p>
                      )}
                    </div>
                  </Card>
                </>
              )}
              <p className="text-xs text-muted">
                갱신 <DateTime value={data.generatedAt} />
              </p>
            </div>
          )
        }}
      </QueryView>
    </>
  )
}

function meterProps(bucket: BudgetBucket) {
  return {
    limitKrw: bucket.limitKrw,
    spentKrw: bucket.spentKrw,
    reservedKrw: bucket.reservedKrw,
    remainingKrw: bucket.remainingKrw,
  }
}

function periodCaption(bucket: BudgetBucket): string {
  const fmt = (iso: string) =>
    new Intl.DateTimeFormat('ko-KR', { timeZone: bucket.timezone, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(iso))
  return `기간 ${fmt(bucket.periodStart)} ~ ${fmt(bucket.periodEnd)} (${bucket.timezone})${bucket.id === null ? ' · 아직 예약이 없어 사용 0원' : ''}`
}

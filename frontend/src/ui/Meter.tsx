import { cn } from '../lib/cn'
import type { ReactNode } from 'react'
import { addDecimal, compareDecimal, percentOf } from '../lib/decimal'
import { budgetState, type BudgetState } from '../lib/budget'
import { Money } from './Display'

/** 단순 진행률(0~100). */
export function ProgressBar({ value, label, tone = 'primary' }: { value: number; label: string; tone?: 'primary' | 'warning' | 'danger' }) {
  const clamped = Math.max(0, Math.min(100, value))
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(clamped)}
      className="h-2 w-full overflow-hidden rounded-full bg-subtle"
    >
      <div
        className={cn('h-full rounded-full', tone === 'danger' ? 'bg-danger' : tone === 'warning' ? 'bg-warning' : 'bg-primary')}
        style={{ width: `${clamped}%` }}
      />
    </div>
  )
}

const STATE_TEXT: Record<BudgetState, string> = {
  ok: '여유',
  near: '소진 임박',
  over: '한도 초과',
  blocked: '전면 차단(한도 0원)',
}

/**
 * 예산 미터: 실제 지출(채움) | 예약(빗금) | 잔여(빈칸). AI Studio의 핵심 시각 요소로 대시보드·사용량·정책 화면에서 같게 쓴다.
 * 비율은 표시용이며 예산 검사는 서버가 한다.
 */
export function BudgetMeter({
  title,
  limitKrw,
  spentKrw,
  reservedKrw,
  remainingKrw,
  caption,
}: {
  title: string
  limitKrw: string
  spentKrw: string
  reservedKrw: string
  remainingKrw: string
  caption?: string
}) {
  const state = budgetState(limitKrw, spentKrw, reservedKrw, remainingKrw)
  const spentPct = percentOf(spentKrw, limitKrw) ?? 0
  const reservedPct = percentOf(reservedKrw, limitKrw) ?? 0
  const spentWidth = Math.min(100, spentPct)
  const reservedWidth = Math.min(100 - spentWidth, reservedPct)
  const usedPct = percentOf(addDecimal(spentKrw, reservedKrw) ?? spentKrw, limitKrw)
  return (
    <div className="@container grid gap-2.5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-base font-semibold">{title}</span>
        <span
          className={cn(
            'text-sm font-medium',
            state === 'over' || state === 'blocked' ? 'text-danger' : state === 'near' ? 'text-warning' : 'text-muted',
          )}
        >
          {STATE_TEXT[state]}
          {usedPct !== null && <span className="num"> · 사용 {usedPct.toFixed(1)}%</span>}
        </span>
      </div>
      <div
        role="img"
        aria-label={`${title}: 한도 대비 실제 지출 ${spentPct.toFixed(1)}%, 예약 ${reservedPct.toFixed(1)}%`}
        className="flex h-3 w-full overflow-hidden rounded-full bg-subtle"
      >
        <div className={cn('h-full', state === 'over' ? 'bg-danger' : 'bg-primary')} style={{ width: `${spentWidth}%` }} />
        <div className="meter-reserved h-full" style={{ width: `${reservedWidth}%` }} />
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-sm @xl:grid-cols-4">
        <Figure label="한도" value={<Money value={limitKrw} />} />
        <Figure label="실제 지출" swatch="bg-primary" value={<Money value={spentKrw} />} />
        <Figure label="예약" swatch="meter-reserved" value={<Money value={reservedKrw} />} />
        <Figure
          label="잔여"
          value={<Money value={remainingKrw} />}
          emphasis={compareDecimal(remainingKrw, '0') < 0 ? 'danger' : undefined}
        />
      </dl>
      {caption && <p className="text-xs text-muted">{caption}</p>}
    </div>
  )
}

function Figure({
  label,
  value,
  swatch,
  emphasis,
}: {
  label: string
  value: ReactNode
  swatch?: string
  emphasis?: 'danger'
}) {
  return (
    <div className="min-w-0">
      <dt className="flex items-center gap-1.5 text-xs text-muted">
        {swatch && <span aria-hidden className={cn('inline-block size-2.5 rounded-sm', swatch)} />}
        {label}
      </dt>
      <dd className={cn('num font-medium break-all', emphasis === 'danger' && 'text-danger')}>{value}</dd>
    </div>
  )
}

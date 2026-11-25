import { ArrowDownRight, ArrowUpRight } from 'lucide-react'
import { Link } from 'react-router'
import type { CostLogEntry } from '../api/admin'
import { sign, signedKrw } from '../lib/decimal'
import { ACTOR, ENTRY_TYPE, REASON, label, text } from '../lib/labels'
import { cn } from '../lib/cn'
import { DataTable } from '../ui/DataTable'
import { DateTime, Decimal, Money } from '../ui/Display'
import { LabelBadge } from '../ui/StatusBadge'

/** 증감 표시: 부호 문자(+/−) + 화살표 아이콘 + 색. 색만으로 의미를 전달하지 않는다. */
export function Delta({ value, kind }: { value: string; kind: 'cost' | 'reserve' }) {
  const s = sign(value)
  if (s === 0) {
    return <span className="num text-muted">0<span className="sr-only"> (변동 없음)</span></span>
  }
  const Icon = s > 0 ? ArrowUpRight : ArrowDownRight
  return (
    <span
      className={cn(
        'num inline-flex items-center justify-end gap-1 whitespace-nowrap',
        s > 0 && (kind === 'cost' ? 'text-danger' : 'text-primary'),
        s < 0 && 'text-success',
      )}
    >
      <Icon aria-hidden className="size-3.5" />
      <span>{signedKrw(value)}</span>
      <span className="sr-only">{s > 0 ? ' 증가' : ' 감소'}</span>
    </span>
  )
}

/** 비용 원장(추가 전용) 표. 회계 장부처럼 시각·유형·금액 열을 고정 순서로 둔다. 수정·삭제 기능 없음. */
export function LedgerTable({ entries }: { entries: CostLogEntry[] }) {
  return (
    <DataTable<CostLogEntry>
      caption="비용 원장"
      rows={entries}
      rowKey={(e) => e.id}
      columns={[
        { key: 'when', header: '발생 시각', cell: (e) => <DateTime value={e.occurredAt} showZone={false} /> },
        { key: 'type', header: '유형', cell: (e) => <span title={ENTRY_TYPE[e.entryType]?.help}><LabelBadge value={label(ENTRY_TYPE, e.entryType)} /></span> },
        { key: 'reservation', header: '관련 예약', cell: (e) => <Link to={`/budget/reservations/${e.reservationId}`} className="hover:text-primary hover:underline">#{e.reservationId}</Link> },
        { key: 'original', header: '원본 금액', align: 'right', cell: (e) => <Money value={e.originalAmount} currency={e.originalCurrency} /> },
        { key: 'rate', header: '적용 환율', align: 'right', hideBelow: 'lg', cell: (e) => (e.originalCurrency === 'KRW' ? <span className="text-muted">—</span> : <Decimal value={e.fxRate} />) },
        { key: 'krw', header: '원화 금액', align: 'right', cell: (e) => <Money value={e.amountKrw} /> },
        { key: 'reserve', header: '예약 증감', align: 'right', cell: (e) => <Delta value={e.reserveDeltaKrw} kind="reserve" /> },
        { key: 'cost', header: '비용 증감', align: 'right', cell: (e) => <Delta value={e.costDeltaKrw} kind="cost" /> },
        { key: 'reason', header: '정산 사유', hideBelow: 'md', cell: (e) => (
          <>
            {text(REASON, e.reasonCode)}
            <span className="block text-xs text-muted">{text(ACTOR, e.actorType)}{e.actorUserId ? ` #${e.actorUserId}` : ''}</span>
          </>
        ) },
      ]}
    />
  )
}

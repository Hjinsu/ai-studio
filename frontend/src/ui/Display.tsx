import { formatDecimal, formatKrw, formatMoney } from '../lib/decimal'
import { browserTimeZone, formatDateTime } from '../lib/time'
import { cn } from '../lib/cn'

/** CurrencyDisplay: KRW는 ₩ + 천 단위, 그 외 통화는 코드 접미. 정밀도 유지(후행 0만 제거). */
export function Money({ value, currency = 'KRW', className }: { value: string | null | undefined; currency?: string; className?: string }) {
  if (value === null || value === undefined) {
    return <span className={className}>—</span>
  }
  return (
    <span className={cn('num', className)} title={`${value} ${currency}`}>
      {currency === 'KRW' ? formatKrw(value) : formatMoney(value, currency)}
    </span>
  )
}

/** 환율: "1 USD = ₩1,500" */
export function Rate({ base, rate }: { base: string; rate: string }) {
  return (
    <span className="num" title={`${rate} KRW per ${base}`}>
      1 {base} = {formatKrw(rate)}
    </span>
  )
}

export function Decimal({ value }: { value: string }) {
  return <span className="num">{formatDecimal(value)}</span>
}

/**
 * DateTimeDisplay: YYYY-MM-DD HH:mm, 지정 시간대(채널·bucket) 또는 브라우저 시간대. 시간대 이름을 표시하고
 * 원래 UTC 값을 title로 제공한다.
 */
export function DateTime({
  value,
  timeZone,
  seconds = false,
  showZone = true,
}: {
  value: string | null | undefined
  timeZone?: string
  seconds?: boolean
  showZone?: boolean
}) {
  if (!value) {
    return <span className="text-muted">—</span>
  }
  const zone = timeZone ?? browserTimeZone()
  return (
    <time dateTime={value} title={`${value} (UTC 저장값)`} className="num whitespace-nowrap">
      {formatDateTime(value, zone, seconds)}
      {showZone && <span className="ml-1 text-xs text-muted">{zone}</span>}
    </time>
  )
}

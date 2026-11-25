/** 시각 표시 형식 통일: YYYY-MM-DD HH:mm (초는 상세에서만). 시간대 이름을 함께 제공한다. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

function partsOf(iso: string, timeZone: string, seconds: boolean): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  const formatted = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    ...(seconds ? { second: '2-digit' } : {}),
    hourCycle: 'h23',
  }).formatToParts(date)
  const get = (type: string) => formatted.find((p) => p.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}${seconds ? `:${get('second')}` : ''}`
}

export function formatDateTime(iso: string | null | undefined, timeZone?: string, seconds = false): string {
  if (!iso) {
    return '—'
  }
  return partsOf(iso, timeZone ?? browserTimeZone(), seconds)
}

/** 남은 시간을 "2일 3시간", "45분" 형태로. 지났으면 null. */
export function remaining(untilIso: string, now = Date.now()): string | null {
  const ms = new Date(untilIso).getTime() - now
  if (Number.isNaN(ms) || ms <= 0) {
    return null
  }
  const minutes = Math.floor(ms / 60000)
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const mins = minutes % 60
  if (days > 0) return `${days}일 ${hours}시간`
  if (hours > 0) return `${hours}시간 ${mins}분`
  return `${Math.max(mins, 1)}분`
}

/** 자주 쓰는 IANA 시간대(입력 제안용). 서버가 ZoneId로 최종 검증한다. */
export function timeZoneSuggestions(): string[] {
  const supported = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : []
  const common = ['Asia/Seoul', 'UTC', 'America/New_York', 'America/Los_Angeles', 'Europe/London', 'Asia/Tokyo']
  return [...new Set([...common, ...supported])]
}

/**
 * 금액·환율 문자열 처리 (ADR-035). API의 DECIMAL 문자열을 JavaScript Number로 바꾸지 않는다.
 * 표시는 문자열 연산(천 단위 구분, 후행 0 제거), 비교·비율은 BigInt 고정소수점으로 계산한다.
 */
const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/

interface Parts {
  negative: boolean
  integer: string
  fraction: string
}

function parts(value: string): Parts | null {
  const match = DECIMAL.exec(value.trim())
  if (!match) {
    return null
  }
  const integer = match[2].replace(/^0+(?=\d)/, '')
  const fraction = (match[3] ?? '').replace(/0+$/, '')
  const zero = /^0*$/.test(integer) && fraction === ''
  return { negative: match[1] === '-' && !zero, integer, fraction }
}

export function isDecimal(value: string): boolean {
  return parts(value) !== null
}

/** 후행 0 제거, 부호 정규화("-0" → "0"). 형식이 아니면 원문 그대로. */
export function trimDecimal(value: string): string {
  const p = parts(value)
  if (!p) {
    return value
  }
  return `${p.negative ? '-' : ''}${p.integer}${p.fraction ? `.${p.fraction}` : ''}`
}

function group(integer: string): string {
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 천 단위 구분 + 후행 0 제거. 예: "1400.1234567891" → "1,400.1234567891" */
export function formatDecimal(value: string): string {
  const p = parts(value)
  if (!p) {
    return value
  }
  return `${p.negative ? '-' : ''}${group(p.integer)}${p.fraction ? `.${p.fraction}` : ''}`
}

/** KRW 표시: "300000.000000" → "₩300,000", "-5.5" → "-₩5.5". */
export function formatKrw(value: string): string {
  const p = parts(value)
  if (!p) {
    return value
  }
  return `${p.negative ? '-' : ''}₩${group(p.integer)}${p.fraction ? `.${p.fraction}` : ''}`
}

/** 원본 통화 금액: KRW는 ₩ 기호, 그 외는 통화 코드 접미. 예: "1.20" USD → "1.2 USD" */
export function formatMoney(value: string, currency: string): string {
  return currency === 'KRW' ? formatKrw(value) : `${formatDecimal(value)} ${currency}`
}

/** 부호를 붙인 증감 표시(증가 "+", 감소 "−"). 0은 부호 없음. */
export function signedKrw(value: string): string {
  const s = sign(value)
  const abs = formatKrw(trimDecimal(value).replace(/^-/, ''))
  return s > 0 ? `+${abs}` : s < 0 ? `−${abs}` : abs
}

export function sign(value: string): -1 | 0 | 1 {
  const p = parts(value)
  if (!p || (/^0*$/.test(p.integer) && p.fraction === '')) {
    return 0
  }
  return p.negative ? -1 : 1
}

const SCALE = 12

/** 고정소수점(소수 12자리) BigInt. 형식이 아니면 null. */
export function toScaled(value: string): bigint | null {
  const p = parts(value)
  if (!p) {
    return null
  }
  const fraction = p.fraction.slice(0, SCALE).padEnd(SCALE, '0')
  const magnitude = BigInt(p.integer + fraction)
  return p.negative ? -magnitude : magnitude
}

function fromScaled(value: bigint): string {
  const negative = value < 0n
  const digits = (negative ? -value : value).toString().padStart(SCALE + 1, '0')
  return trimDecimal(`${negative ? '-' : ''}${digits.slice(0, -SCALE)}.${digits.slice(-SCALE)}`)
}

/** 정밀 덧셈(문자열). 형식이 아니면 null. */
export function addDecimal(a: string, b: string): string | null {
  const x = toScaled(a)
  const y = toScaled(b)
  return x === null || y === null ? null : fromScaled(x + y)
}

export function compareDecimal(a: string, b: string): -1 | 0 | 1 {
  const x = toScaled(a)
  const y = toScaled(b)
  if (x === null || y === null) {
    return 0
  }
  return x === y ? 0 : x < y ? -1 : 1
}

/** 가장 작은 값(문자열 그대로 반환). */
export function minDecimal(values: string[]): string | null {
  return values.reduce<string | null>((min, v) => (min === null || compareDecimal(v, min) < 0 ? v : min), null)
}

/**
 * part / whole 비율(%)을 소수 1자리로 계산한다. whole이 0 이하이면 null(0원 한도 = 전면 차단, 비율 없음).
 * 표시용 비율만 Number로 돌려주며 금액 자체는 변환하지 않는다.
 */
export function percentOf(part: string, whole: string): number | null {
  const p = toScaled(part)
  const w = toScaled(whole)
  if (p === null || w === null || w <= 0n) {
    return null
  }
  const permille = (p * 1000n) / w
  return Number(permille) / 10
}

/** 입력값이 허용 형식(정수부 최대 intDigits, 소수 최대 fractionDigits)인지. */
export function matchesAmount(value: string, intDigits: number, fractionDigits: number, allowNegative = false): boolean {
  const pattern = new RegExp(
    `^${allowNegative ? '-?' : ''}(0|[1-9][0-9]{0,${intDigits - 1}})(\\.[0-9]{1,${fractionDigits}})?$`,
  )
  return pattern.test(value)
}

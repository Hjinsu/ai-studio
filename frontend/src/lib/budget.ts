import { addDecimal, compareDecimal, sign } from './decimal'

export type BudgetState = 'ok' | 'near' | 'exhausted' | 'over' | 'blocked'

/**
 * (지출+예약)을 한도와 정확히(문자열 고정소수점) 비교한다. 한도 0원은 전면 차단, 한도와 같으면 한도 소진,
 * 넘으면 한도 초과, 80% 이상은 소진 임박.
 */
export function budgetState(limitKrw: string, spentKrw: string, reservedKrw: string, remainingKrw: string): BudgetState {
  if (sign(limitKrw) <= 0) return 'blocked'
  if (sign(remainingKrw) < 0) return 'over'
  const used = addDecimal(spentKrw, reservedKrw) ?? spentKrw
  const versusLimit = compareDecimal(used, limitKrw)
  if (versusLimit > 0) return 'over'
  if (versusLimit === 0) return 'exhausted'
  // 80% 경계: used * 10 >= limit * 8 (정밀 비교)
  return compareDecimal(addDecimalTimes(used, 10), addDecimalTimes(limitKrw, 8)) >= 0 ? 'near' : 'ok'
}

/** value × factor(작은 양의 정수)를 반복 덧셈으로 계산(문자열 정밀도 유지). */
function addDecimalTimes(value: string, factor: number): string {
  let total = '0'
  for (let i = 0; i < factor; i++) {
    total = addDecimal(total, value) ?? total
  }
  return total
}

/**
 * 신규 지출 표시 상태(UI-RV-01). 관리자 설정(spendingEnabled)과 서버의 신규 예약 차단을 구분한다. 서버는 관리자 차단 또는
 * 정합성 ERROR이면 신규 예약을 거부한다(Fail Closed). 허용으로 보이더라도 정책·채널·환율·한도 등 나머지 조건은 예약할 때
 * 서버가 다시 검사하므로 "예약 가능"으로 단정하지 않는다.
 */
export interface SpendingView {
  /** 서버가 신규 예약을 확실히 거부하는 상태 */
  blocked: boolean
  adminEnabled: boolean
  consistencyError: boolean
  consistencyUnknown: boolean
}

export function spendingView(spendingEnabled: boolean, consistencyStatus: string): SpendingView {
  const consistencyError = consistencyStatus === 'ERROR'
  return {
    blocked: !spendingEnabled || consistencyError,
    adminEnabled: spendingEnabled,
    consistencyError,
    consistencyUnknown: consistencyStatus !== 'OK' && !consistencyError,
  }
}

import { addDecimal, percentOf, sign } from './decimal'

export type BudgetState = 'ok' | 'near' | 'over' | 'blocked'

/** 사용률(지출+예약)/한도로 상태를 판단한다. 한도 0원은 전면 차단. 80% 이상은 소진 임박. */
export function budgetState(limitKrw: string, spentKrw: string, reservedKrw: string, remainingKrw: string): BudgetState {
  if (sign(limitKrw) <= 0) return 'blocked'
  if (sign(remainingKrw) < 0) return 'over'
  const used = percentOf(addDecimal(spentKrw, reservedKrw) ?? spentKrw, limitKrw) ?? 0
  return used >= 100 ? 'over' : used >= 80 ? 'near' : 'ok'
}

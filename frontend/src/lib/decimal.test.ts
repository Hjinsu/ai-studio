import { describe, expect, it } from 'vitest'
import {
  addDecimal,
  compareDecimal,
  formatDecimal,
  formatKrw,
  formatMoney,
  matchesAmount,
  minDecimal,
  percentOf,
  signedKrw,
  trimDecimal,
} from './decimal'

describe('decimal formatting keeps precision without Number', () => {
  it('formats KRW with symbol, grouping and trailing-zero trimming', () => {
    expect(formatKrw('300000.000000')).toBe('₩300,000')
    expect(formatKrw('1400.123457')).toBe('₩1,400.123457')
    expect(formatKrw('-5.500')).toBe('-₩5.5')
    expect(formatKrw('0.000000')).toBe('₩0')
    expect(formatKrw('-0.000')).toBe('₩0')
    // Number로는 표현할 수 없는 자릿수
    expect(formatKrw('123456789012345678.000001')).toBe('₩123,456,789,012,345,678.000001')
  })

  it('formats rates and foreign amounts', () => {
    expect(formatDecimal('1400.1234567891')).toBe('1,400.1234567891')
    expect(formatMoney('1.20', 'USD')).toBe('1.2 USD')
    expect(formatMoney('300000.000000', 'KRW')).toBe('₩300,000')
    expect(trimDecimal('10.000')).toBe('10')
    expect(trimDecimal('300000')).toBe('300000')
    expect(formatKrw('not-a-number')).toBe('not-a-number')
  })

  it('shows signed deltas', () => {
    expect(signedKrw('1120.098765')).toBe('+₩1,120.098765')
    expect(signedKrw('-1120.098765')).toBe('−₩1,120.098765')
    expect(signedKrw('0.000')).toBe('₩0')
  })

  it('compares, adds and finds minimum exactly', () => {
    expect(compareDecimal('0.1', '0.10')).toBe(0)
    expect(compareDecimal('9007199254740993', '9007199254740992')).toBe(1)
    expect(addDecimal('0.1', '0.2')).toBe('0.3')
    expect(addDecimal('21000.000000', '4200.370371')).toBe('25200.370371')
    expect(minDecimal(['4799.629629', '169349.129629', '-1'])).toBe('-1')
    expect(minDecimal([])).toBeNull()
  })

  it('computes display percentages; zero limit has no ratio', () => {
    expect(percentOf('21000', '30000')).toBe(70)
    expect(percentOf('25200.370371', '30000')).toBe(84)
    expect(percentOf('1', '0')).toBeNull()
  })

  it('validates amount input shape', () => {
    expect(matchesAmount('300000', 18, 6)).toBe(true)
    expect(matchesAmount('300,000', 18, 6)).toBe(false)
    expect(matchesAmount('1.1234567', 18, 6)).toBe(false)
    expect(matchesAmount('-50', 12, 12, true)).toBe(true)
    expect(matchesAmount('-50', 12, 12)).toBe(false)
    expect(matchesAmount('007', 18, 6)).toBe(false)
  })
})

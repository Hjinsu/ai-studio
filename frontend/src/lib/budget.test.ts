/// <reference types="node" />
import { describe, expect, it } from 'vitest'
import { budgetState, spendingView } from './budget'
import { STYLE, languageLabel, text } from './labels'
import { readFileSync } from 'node:fs'

describe('budgetState (UI-RV-A02)', () => {
  it('distinguishes exactly 100% (exhausted) from over the limit with full precision', () => {
    expect(budgetState('10000.000000', '6000.5', '3999.5', '0')).toBe('exhausted')
    expect(budgetState('10000', '10000.000000', '0', '0.000000')).toBe('exhausted')
    expect(budgetState('10000', '10000.000001', '0', '-0.000001')).toBe('over')
    expect(budgetState('10000', '9999.999999', '0', '0.000001')).toBe('near')
    expect(budgetState('10000', '7999.999999', '0', '2000.000001')).toBe('ok')
    expect(budgetState('10000', '8000', '0', '2000')).toBe('near')
    expect(budgetState('0', '0', '0', '0')).toBe('blocked')
  })
})

describe('spendingView (UI-RV-01)', () => {
  it.each([
    [false, 'OK', { blocked: true, adminEnabled: false, consistencyError: false, consistencyUnknown: false }],
    [false, 'ERROR', { blocked: true, adminEnabled: false, consistencyError: true, consistencyUnknown: false }],
    [true, 'OK', { blocked: false, adminEnabled: true, consistencyError: false, consistencyUnknown: false }],
    [true, 'ERROR', { blocked: true, adminEnabled: true, consistencyError: true, consistencyUnknown: false }],
    [true, 'UNKNOWN', { blocked: false, adminEnabled: true, consistencyError: false, consistencyUnknown: true }],
  ])('admin %s + consistency %s', (enabled, status, expected) => {
    expect(spendingView(enabled, status)).toEqual(expected)
  })
})

describe('display labels (UI-F03, F04, F07)', () => {
  it('shows Korean names for known values and the raw value otherwise', () => {
    expect(text(STYLE, 'DOCUMENTARY')).toBe('다큐멘터리')
    expect(text(STYLE, 'CINEMATIC')).toBe('시네마틱')
    expect(text(STYLE, '차분한 톤')).toBe('차분한 톤')
    expect(languageLabel('en')).toBe('영어(en)')
    expect(languageLabel('en-US')).toBe('영어(en-US)')
    expect(languageLabel('ko')).toBe('한국어(ko)')
    expect(languageLabel('tlh')).toBe('tlh')
  })
})

/** WCAG 상대 휘도 대비(1.4.11 비텍스트 대비 3:1). */
function contrast(a: string, b: string): number {
  const lum = (hex: string) => {
    const [r, g, b2] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b2
  }
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

// vitest는 frontend 디렉터리에서 실행된다
const css = readFileSync('src/styles.css', 'utf-8')

function tokens(block: string): Record<string, string> {
  const body = css.slice(css.indexOf(block)).split('}')[0]
  return Object.fromEntries([...body.matchAll(/--c-([a-z-]+):\s*(#[0-9a-f]{6})/g)].map((m) => [m[1], m[2]]))
}

describe('input border token (UI-RV-06)', () => {
  it.each([[':root {'], ['.dark {']])('%s keeps input borders at 3:1 or more against every surface', (block) => {
    const t = tokens(block)
    for (const surface of ['surface', 'bg', 'subtle']) {
      expect(contrast(t['input-border'], t[surface])).toBeGreaterThanOrEqual(3)
    }
  })
})

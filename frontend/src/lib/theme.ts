import { useSyncExternalStore } from 'react'

/** 라이트/다크 테마. 저장 키는 index.html의 초기 스크립트와 같다. 저장값이 없으면 OS 설정을 따른다. */
export type Theme = 'light' | 'dark'

const KEY = 'ai-studio.theme'
const listeners = new Set<() => void>()

function systemDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches
}

export function currentTheme(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

export function applyStoredTheme(): void {
  let stored: string | null
  try {
    stored = localStorage.getItem(KEY)
  } catch {
    stored = null
  }
  const dark = stored === 'dark' || (stored !== 'light' && systemDark())
  document.documentElement.classList.toggle('dark', dark)
}

export function setTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  try {
    localStorage.setItem(KEY, theme)
  } catch {
    // 저장소를 쓸 수 없으면 현재 세션에만 적용
  }
  listeners.forEach((listener) => listener())
}

export function useTheme(): [Theme, (theme: Theme) => void] {
  const theme = useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    currentTheme,
    () => 'light' as Theme,
  )
  return [theme, setTheme]
}

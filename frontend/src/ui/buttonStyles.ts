import { cn } from '../lib/cn'

/**
 * 버튼 위계: primary(화면의 주 작업 1개) · secondary(일반) · ghost(보조) · danger(파괴·차단) ·
 * risk(비용 발생을 허용하는 작업 — 저장 버튼과 구분되는 경고 테두리, 예: 지출 허용).
 */
export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'risk'

const VARIANT: Record<ButtonVariant, string> = {
  primary: 'bg-primary text-primary-fg hover:bg-primary-hover border-transparent',
  secondary: 'bg-surface text-text border-line-strong hover:bg-subtle',
  ghost: 'bg-transparent text-text border-transparent hover:bg-subtle',
  danger: 'bg-danger text-white border-transparent hover:bg-danger-hover dark:text-bg',
  risk: 'bg-warning-soft text-warning border-warning hover:bg-surface',
}

export function buttonClass(variant: ButtonVariant = 'secondary', size: 'sm' | 'md' = 'md', className?: string): string {
  return cn(
    'inline-flex items-center justify-center gap-1.5 rounded-md border font-medium whitespace-nowrap',
    'transition-colors disabled:cursor-not-allowed disabled:opacity-55',
    size === 'sm' ? 'h-8 px-2.5 text-sm' : 'h-9 px-3.5 text-base',
    VARIANT[variant],
    className,
  )
}

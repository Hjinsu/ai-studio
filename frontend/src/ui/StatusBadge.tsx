import type { ReactNode } from 'react'
import { cn } from '../lib/cn'
import type { Label, Tone } from '../lib/labels'

const TONE: Record<Tone, string> = {
  neutral: 'bg-subtle text-muted border-line',
  primary: 'bg-primary-soft text-primary border-transparent',
  success: 'bg-success-soft text-success border-transparent',
  warning: 'bg-warning-soft text-warning border-transparent',
  danger: 'bg-danger-soft text-danger border-transparent',
}

/** 상태 배지. 색과 함께 항상 텍스트로 의미를 전달한다(색만으로 구분 금지). */
export function StatusBadge({ tone = 'neutral', children, icon }: { tone?: Tone; children: ReactNode; icon?: ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium whitespace-nowrap',
        TONE[tone],
      )}
    >
      {icon}
      {children}
    </span>
  )
}

export function LabelBadge({ value }: { value: Label }) {
  return <StatusBadge tone={value.tone}>{value.text}</StatusBadge>
}

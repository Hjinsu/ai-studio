import type { ReactNode } from 'react'
import { cn } from '../lib/cn'

/** 패널(테두리만, 그림자 없음). 제목과 작업 버튼을 머리에 둘 수 있다. */
export function Card({
  title,
  description,
  actions,
  children,
  className,
  tone,
}: {
  title?: ReactNode
  description?: ReactNode
  actions?: ReactNode
  children?: ReactNode
  className?: string
  tone?: 'warning' | 'danger'
}) {
  return (
    <section
      className={cn(
        'rounded-lg border bg-surface',
        tone === 'danger' ? 'border-danger' : tone === 'warning' ? 'border-warning' : 'border-line',
        className,
      )}
    >
      {(title || actions) && (
        <header className="flex flex-wrap items-start justify-between gap-3 border-b border-line px-5 py-3.5">
          <div className="min-w-0">
            {title && <h2 className="text-md font-semibold">{title}</h2>}
            {description && <p className="mt-0.5 text-sm text-muted">{description}</p>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className="p-5">{children}</div>
    </section>
  )
}

/** 요약 지표. 값은 이미 형식화된 문자열(금액은 CurrencyDisplay로). */
export function StatCard({
  label,
  value,
  detail,
  tone,
  icon,
  children,
}: {
  label: string
  value: ReactNode
  detail?: ReactNode
  tone?: 'success' | 'warning' | 'danger'
  icon?: ReactNode
  children?: ReactNode
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-lg border border-line bg-surface px-4 py-3.5">
      <div className="flex items-center gap-1.5 text-sm text-muted">
        {icon}
        <span>{label}</span>
      </div>
      <div
        className={cn(
          'num text-lg font-semibold break-all',
          tone === 'success' && 'text-success',
          tone === 'warning' && 'text-warning',
          tone === 'danger' && 'text-danger',
        )}
      >
        {value}
      </div>
      {detail && <div className="text-xs text-muted">{detail}</div>}
      {children}
    </div>
  )
}

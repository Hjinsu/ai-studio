import type { ReactNode } from 'react'
import { AlertTriangle, Inbox, Lock, WifiOff } from 'lucide-react'
import { describeError } from '../lib/errors'
import { Button } from './Button'
import { cn } from '../lib/cn'

/** 데이터 없음: 무엇이 없는지 + 다음 행동. 가상 데이터를 만들지 않는다. */
export function EmptyState({ title, description, action, icon }: { title: string; description?: ReactNode; action?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-line-strong px-6 py-10 text-center">
      <span aria-hidden className="text-muted">
        {icon ?? <Inbox className="size-6" />}
      </span>
      <p className="text-base font-medium">{title}</p>
      {description && <p className="max-w-md text-sm text-muted">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}

/** 로딩 자리표시(스크린리더에는 "불러오는 중"). */
export function LoadingSkeleton({ rows = 3, className }: { rows?: number; className?: string }) {
  return (
    <div role="status" aria-live="polite" className={cn('grid gap-2', className)}>
      <span className="sr-only">불러오는 중…</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} aria-hidden className="h-9 animate-pulse rounded-md bg-subtle" style={{ opacity: 1 - i * 0.18 }} />
      ))}
    </div>
  )
}

/** 조회 실패: 원인 종류별 안내(권한·세션·연결), 서버 메시지, 추적 ID, 다시 시도. */
export function ErrorState({ error, onRetry, compact = false }: { error: unknown; onRetry?: () => void; compact?: boolean }) {
  const view = describeError(error)
  const Icon = view.kind === 'forbidden' || view.kind === 'unauthorized' ? Lock : view.kind === 'network' ? WifiOff : AlertTriangle
  return (
    <div
      role="alert"
      className={cn(
        'flex gap-3 rounded-lg border border-danger/40 bg-danger-soft text-text',
        compact ? 'px-3 py-2.5' : 'px-4 py-3.5',
      )}
    >
      <Icon aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
      <div className="grid min-w-0 gap-1">
        <strong className="text-base font-semibold">{view.title}</strong>
        {view.message && <p className="text-sm break-words">{view.message}</p>}
        {view.details.length > 0 && (
          <ul className="list-disc pl-5 text-sm">
            {view.details.map((detail) => (
              <li key={detail}>{detail}</li>
            ))}
          </ul>
        )}
        {view.traceId && <small className="num text-xs text-muted">추적 ID {view.traceId}</small>}
        {onRetry && view.kind !== 'unauthorized' && (
          <div>
            <Button size="sm" onClick={onRetry} className="mt-1">
              다시 시도
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}

interface QueryLike<T> {
  isPending: boolean
  isError: boolean
  error: unknown
  data: T | undefined
  refetch?: () => unknown
}

/** 조회 상태 표준 처리: 로딩 → 스켈레톤, 실패 → ErrorState(다시 시도), 성공 → children(data). */
export function QueryView<T>({ query, children, rows }: { query: QueryLike<T>; children: (data: T) => ReactNode; rows?: number }) {
  if (query.isPending) {
    return <LoadingSkeleton rows={rows} />
  }
  if (query.isError || query.data === undefined) {
    return <ErrorState error={query.error} onRetry={query.refetch ? () => void query.refetch?.() : undefined} />
  }
  return <>{children(query.data)}</>
}

export function InlineNotice({ tone = 'neutral', children, icon }: { tone?: 'neutral' | 'warning' | 'danger' | 'primary'; children: ReactNode; icon?: ReactNode }) {
  return (
    <div
      className={cn(
        'flex gap-2.5 rounded-md border px-3 py-2.5 text-sm',
        tone === 'warning' && 'border-warning/40 bg-warning-soft',
        tone === 'danger' && 'border-danger/40 bg-danger-soft',
        tone === 'primary' && 'border-primary/30 bg-primary-soft',
        tone === 'neutral' && 'border-line bg-subtle',
      )}
    >
      {icon && <span aria-hidden className="mt-0.5 shrink-0">{icon}</span>}
      <div className="min-w-0">{children}</div>
    </div>
  )
}

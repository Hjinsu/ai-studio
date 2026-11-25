import type { ReactNode } from 'react'
import { cn } from '../lib/cn'

export interface RevisionChange {
  field: string
  before: ReactNode
  after: ReactNode
}

export interface RevisionEntry {
  key: string
  title: ReactNode
  meta: ReactNode
  note?: string | null
  /** 직전 revision 대비 변경. undefined면 비교 대상 없음(최초 등), 빈 배열이면 변경 없음. */
  changes?: RevisionChange[]
  current?: boolean
  body?: ReactNode
}

/**
 * revision 타임라인(최신이 위). 각 항목에 직전 revision 대비 변경 항목을 "이전 → 이후"로 보여준다.
 * 채널 설정 이력과 예산 정책 이력에서 같이 쓴다. 기존 revision은 불변이므로 편집 기능이 없다.
 */
export function RevisionHistory({ entries, label }: { entries: RevisionEntry[]; label: string }) {
  return (
    <ol aria-label={label} className="relative grid gap-0">
      {entries.map((entry, index) => (
        <li key={entry.key} className="relative grid grid-cols-[1.25rem_1fr] gap-3 pb-5 last:pb-0">
          <div aria-hidden className="relative flex justify-center">
            <span
              className={cn(
                'mt-1.5 size-2.5 rounded-full border-2',
                entry.current ? 'border-primary bg-primary' : 'border-line-strong bg-surface',
              )}
            />
            {index < entries.length - 1 && <span className="absolute top-5 bottom-[-0.25rem] w-px bg-line" />}
          </div>
          <div className="min-w-0">
            <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
              <span className="text-base font-semibold">{entry.title}</span>
              <span className="text-sm text-muted">{entry.meta}</span>
            </div>
            {entry.note && <p className="mt-1 text-sm">{entry.note}</p>}
            {entry.changes === undefined ? null : entry.changes.length === 0 ? (
              <p className="mt-1.5 text-sm text-muted">이전 revision과 같은 설정입니다.</p>
            ) : (
              <dl className="mt-2 grid gap-1.5 rounded-md border border-line bg-subtle/60 px-3 py-2.5 text-sm">
                {entry.changes.map((change) => (
                  <div key={change.field} className="grid gap-0.5 sm:grid-cols-[10rem_1fr] sm:gap-3">
                    <dt className="text-muted">{change.field}</dt>
                    <dd className="min-w-0 break-words">
                      <span className="text-muted line-through decoration-1">{change.before}</span>
                      <span aria-hidden className="mx-1.5 text-muted">→</span>
                      <span className="sr-only">에서 </span>
                      <span className="font-medium">{change.after}</span>
                      <span className="sr-only">로 변경</span>
                    </dd>
                  </div>
                ))}
              </dl>
            )}
            {entry.body}
          </div>
        </li>
      ))}
    </ol>
  )
}

import type { ReactNode } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '../lib/cn'
import { IconButton } from './Button'

export interface Column<T> {
  key: string
  header: string
  cell: (row: T) => ReactNode
  align?: 'left' | 'right'
  /** 작은 화면에서 숨겨도 되는 보조 열 */
  hideBelow?: 'md' | 'lg'
  className?: string
}

/**
 * 데이터 표. 좁은 화면에서는 표 영역만 가로 스크롤된다(페이지 전체가 밀리지 않음). 숫자 열은 오른쪽 정렬.
 * 정렬·필터는 서버 API가 지원하는 범위에서 화면이 따로 제공한다.
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  caption,
  rowTone,
}: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  caption: string
  rowTone?: (row: T) => 'warning' | 'danger' | undefined
}) {
  return (
    <div className="relative overflow-x-auto rounded-lg border border-line bg-surface">
      <table className="w-full min-w-[40rem] border-collapse text-base">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-line bg-subtle/60">
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={cn(
                  'px-4 py-2.5 text-left text-sm font-medium whitespace-nowrap text-muted',
                  column.align === 'right' && 'text-right',
                  column.hideBelow === 'md' && 'hidden md:table-cell',
                  column.hideBelow === 'lg' && 'hidden lg:table-cell',
                )}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const tone = rowTone?.(row)
            return (
              <tr
                key={rowKey(row)}
                className={cn(
                  'border-b border-line last:border-b-0',
                  tone === 'danger' && 'bg-danger-soft/50 shadow-[inset_3px_0_0_var(--c-danger)]',
                  tone === 'warning' && 'bg-warning-soft/50 shadow-[inset_3px_0_0_var(--c-warning)]',
                )}
              >
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={cn(
                      'px-4 py-2.5 align-middle',
                      column.align === 'right' && 'num text-right',
                      column.hideBelow === 'md' && 'hidden md:table-cell',
                      column.hideBelow === 'lg' && 'hidden lg:table-cell',
                      column.className,
                    )}
                  >
                    {column.cell(row)}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/** 페이지 이동. 전체 건수와 현재 페이지를 텍스트로도 알린다. */
export function Pagination({
  page,
  totalPages,
  totalElements,
  onChange,
}: {
  page: number
  totalPages: number
  totalElements?: number
  onChange: (page: number) => void
}) {
  if (totalPages <= 1 && totalElements === undefined) {
    return null
  }
  return (
    <nav aria-label="페이지" className="mt-3 flex items-center justify-between gap-3 text-sm text-muted">
      <span className="num">{totalElements !== undefined ? `총 ${totalElements.toLocaleString('ko-KR')}건` : ''}</span>
      {totalPages > 1 && (
        <div className="flex items-center gap-1">
          <IconButton
            label="이전 페이지"
            size="sm"
            variant="secondary"
            disabled={page <= 0}
            onClick={() => onChange(page - 1)}
            icon={<ChevronLeft aria-hidden className="size-4" />}
          />
          <span className="num px-2" aria-current="page">
            {page + 1} / {totalPages}
          </span>
          <IconButton
            label="다음 페이지"
            size="sm"
            variant="secondary"
            disabled={page + 1 >= totalPages}
            onClick={() => onChange(page + 1)}
            icon={<ChevronRight aria-hidden className="size-4" />}
          />
        </div>
      )}
    </nav>
  )
}

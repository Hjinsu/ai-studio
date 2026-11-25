import { cloneElement, useEffect, useId, useRef, type ReactElement, type ReactNode } from 'react'
import { describeError } from './format'

export function Loading() {
  return <p role="status">불러오는 중…</p>
}

export function Empty({ children = '항목이 없습니다.' }: { children?: ReactNode }) {
  return <p className="empty">{children}</p>
}

/** 공통 오류 표시: 코드별 안내, 서버 메시지, 필드 오류, 추적 ID. */
export function ErrorAlert({ error }: { error: unknown }) {
  if (error === null || error === undefined) {
    return null
  }
  const view = describeError(error)
  return (
    <div role="alert" className="error">
      <strong>{view.title}</strong>
      {view.message && <p>{view.message}</p>}
      {view.details.length > 0 && (
        <ul>
          {view.details.map((detail) => (
            <li key={detail}>{detail}</li>
          ))}
        </ul>
      )}
      {view.traceId && <small>추적 ID: {view.traceId}</small>}
    </div>
  )
}

interface QueryLike<T> {
  isPending: boolean
  isError: boolean
  error: unknown
  data: T | undefined
}

/** 로딩·실패를 표준 방식으로 표시하고, 성공 시 데이터로 children을 렌더링한다. */
export function QueryView<T>({ query, children }: { query: QueryLike<T>; children: (data: T) => ReactNode }) {
  if (query.isPending) {
    return <Loading />
  }
  if (query.isError || query.data === undefined) {
    return <ErrorAlert error={query.error} />
  }
  return <>{children(query.data)}</>
}

export function Pager({
  page,
  totalPages,
  onChange,
}: {
  page: number
  totalPages: number
  onChange: (page: number) => void
}) {
  if (totalPages <= 1) {
    return null
  }
  return (
    <nav aria-label="페이지" className="pager">
      <button type="button" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        이전
      </button>
      <span>
        {page + 1} / {totalPages}
      </span>
      <button type="button" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        다음
      </button>
    </nav>
  )
}

/**
 * 위험 작업 확인 대화상자(docs/12_FRONTEND.md). 열리면 확인 버튼에 포커스, Esc로 취소.
 * children에 사유 입력 등 추가 필드를 둘 수 있다.
 */
export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  danger = false,
  busy = false,
  confirmDisabled = false,
  onConfirm,
  onCancel,
  children,
}: {
  open: boolean
  title: string
  confirmLabel: string
  danger?: boolean
  busy?: boolean
  confirmDisabled?: boolean
  onConfirm: () => void
  onCancel: () => void
  children?: ReactNode
}) {
  const titleId = useId()
  const cancelRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (open) {
      cancelRef.current?.focus()
    }
  }, [open])
  if (!open) {
    return null
  }
  return (
    <div className="dialog-backdrop">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="dialog"
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            onCancel()
          }
        }}
      >
        <h3 id={titleId}>{title}</h3>
        {children}
        <div className="actions">
          <button type="button" ref={cancelRef} onClick={onCancel} disabled={busy}>
            취소
          </button>
          <button
            type="button"
            className={danger ? 'danger' : 'primary'}
            onClick={onConfirm}
            disabled={busy || confirmDisabled}
          >
            {busy ? '처리 중…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * 입력 필드. 레이블은 이름만 갖고(접근 가능한 이름 = 레이블), 힌트는 aria-describedby로 연결한다.
 * 자식은 단일 입력 요소여야 한다.
 */
export function Field({ label, children, hint }: { label: string; children: ReactElement<FieldChildProps>; hint?: string }) {
  const inputId = useId()
  const hintId = useId()
  const control = cloneElement(children, {
    id: inputId,
    'aria-describedby': hint ? hintId : undefined,
  })
  return (
    <div className="field">
      <label htmlFor={inputId}>{label}</label>
      {control}
      {hint && <small id={hintId}>{hint}</small>}
    </div>
  )
}

interface FieldChildProps {
  id?: string
  'aria-describedby'?: string
}

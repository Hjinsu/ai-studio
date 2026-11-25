import {
  cloneElement,
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactElement,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react'
import { cn } from '../lib/cn'
import { timeZoneSuggestions } from '../lib/time'

const CONTROL =
  'w-full rounded-md border border-line-strong bg-surface px-3 text-base text-text placeholder:text-muted ' +
  'disabled:cursor-not-allowed disabled:opacity-60 aria-[invalid=true]:border-danger'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(
  { className, ...rest },
  ref,
) {
  return <input ref={ref} className={cn(CONTROL, 'h-9', className)} {...rest} />
})

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(function Select(
  { className, ...rest },
  ref,
) {
  return <select ref={ref} className={cn(CONTROL, 'h-9 pr-8', className)} {...rest} />
})

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function Textarea(
  { className, ...rest },
  ref,
) {
  return <textarea ref={ref} className={cn(CONTROL, 'min-h-20 py-2', className)} {...rest} />
})

interface ControlProps {
  id?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
}

/**
 * 레이블 + 입력 + 도움말 + 오류. 레이블은 이름만 갖고(접근 가능한 이름), 도움말·오류는 aria-describedby로 연결한다.
 * 자식은 단일 입력 요소.
 */
export function FormField({
  label,
  hint,
  error,
  optional,
  children,
  className,
}: {
  label: string
  hint?: ReactNode
  error?: string
  optional?: boolean
  children: ReactElement<ControlProps>
  className?: string
}) {
  const id = useId()
  const hintId = useId()
  const errorId = useId()
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className={cn('grid gap-1.5', className)}>
      <label htmlFor={id} className="text-sm font-medium text-text">
        {label}
        {optional && <span className="ml-1 font-normal text-muted">(선택)</span>}
      </label>
      {cloneElement(children, { id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {hint && (
        <p id={hintId} className="text-xs text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-xs font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  )
}

/**
 * 날짜·시각 입력(브라우저 시간대 기준 datetime-local). 값은 로컬 문자열이므로 제출할 때 new Date(value).toISOString()으로 UTC로 바꾼다.
 */
export const DateTimePicker = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function DateTimePicker(props, ref) {
    return <Input ref={ref} type="datetime-local" {...props} />
  },
)

/** IANA 시간대 입력(목록 제안 + 직접 입력). 서버가 ZoneId로 최종 검증한다. */
export const TimeZoneInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function TimeZoneInput(
  props,
  ref,
) {
  const listId = useId()
  return (
    <>
      <Input ref={ref} list={listId} maxLength={64} autoComplete="off" {...props} />
      <datalist id={listId}>
        {timeZoneSuggestions().map((zone) => (
          <option key={zone} value={zone} />
        ))}
      </datalist>
    </>
  )
})

/** 폼 섹션: 관련 설정을 묶는 제목 + 설명. */
export function FormSection({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <fieldset className="grid gap-4 border-t border-line pt-5 first:border-t-0 first:pt-0">
      <legend className="contents">
        <span className="block text-md font-semibold text-text">{title}</span>
      </legend>
      {description && <p className="-mt-2 max-w-prose text-sm text-muted">{description}</p>}
      {children}
    </fieldset>
  )
}

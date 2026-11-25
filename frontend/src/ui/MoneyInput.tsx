import { forwardRef, type InputHTMLAttributes } from 'react'
import { formatKrw, isDecimal } from '../lib/decimal'
import { Input } from './Form'

/**
 * 원화 금액 입력. 값은 숫자 문자열 그대로(쉼표 없이) API에 보내고, 입력란 옆에 단위와 천 단위 미리보기를 보여준다.
 * 쉼표를 입력해도 제거한다. Number 변환 없음.
 */
export const KrwInput = forwardRef<
  HTMLInputElement,
  Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'> & { value: string; onChange: (value: string) => void }
>(function KrwInput({ value, onChange, id, ...rest }, ref) {
  const previewId = id ? `${id}-preview` : undefined
  return (
    <div>
      <div className="relative">
        <Input
          ref={ref}
          id={id}
          inputMode="decimal"
          value={value}
          onChange={(e) => onChange(e.target.value.replace(/[,\s₩]/g, ''))}
          className="num pr-10 text-right"
          {...rest}
          aria-describedby={[rest['aria-describedby'], previewId].filter(Boolean).join(' ') || undefined}
        />
        <span aria-hidden className="pointer-events-none absolute inset-y-0 right-3 flex items-center text-sm text-muted">
          원
        </span>
      </div>
      <p id={previewId} className="num mt-1 text-right text-xs text-muted" aria-live="polite">
        {value && isDecimal(value) ? formatKrw(value) : ' '}
      </p>
    </div>
  )
})

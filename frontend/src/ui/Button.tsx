import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '../lib/cn'
import { buttonClass, type ButtonVariant } from './buttonStyles'
import { Link, type LinkProps } from 'react-router'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: 'sm' | 'md'
  loading?: boolean
  icon?: ReactNode
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading = false, icon, className, children, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={buttonClass(variant, size, className)}
      {...rest}
    >
      {loading ? <Loader2 aria-hidden className="size-4 animate-spin" /> : icon}
      {children}
    </button>
  )
})

/** 아이콘 전용 버튼. 접근 가능한 이름(label)이 필수다. */
export const IconButton = forwardRef<HTMLButtonElement, Omit<ButtonProps, 'children'> & { label: string }>(
  function IconButton({ label, icon, className, variant = 'ghost', size = 'md', ...rest }, ref) {
    return (
      <Button
        ref={ref}
        aria-label={label}
        variant={variant}
        size={size}
        className={cn(size === 'sm' ? 'w-8 px-0' : 'w-9 px-0', className)}
        icon={icon}
        {...rest}
      />
    )
  },
)

/** 버튼 모양의 링크(이동). 링크 안에 버튼을 넣지 않는다. */
export function LinkButton({
  variant = 'secondary',
  size = 'md',
  icon,
  className,
  children,
  ...rest
}: LinkProps & { variant?: ButtonVariant; size?: 'sm' | 'md'; icon?: ReactNode }) {
  return (
    <Link className={buttonClass(variant, size, className)} {...rest}>
      {icon}
      {children}
    </Link>
  )
}

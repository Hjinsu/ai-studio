import type { ReactNode } from 'react'
import * as RadixDialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import { Button, IconButton } from './Button'
import { cn } from '../lib/cn'

/**
 * 대화상자(Radix: 포커스 가두기, Esc 닫기, 바깥 클릭 닫기, 열기 전 포커스 복귀). 폼 등록·수정에 쓴다.
 */
export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  size = 'md',
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  children: ReactNode
  footer?: ReactNode
  size?: 'md' | 'lg'
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/45" />
        <RadixDialog.Content
          className={cn(
            'fixed top-1/2 left-1/2 z-50 flex max-h-[90vh] w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2 flex-col',
            'rounded-lg border border-line bg-surface shadow-overlay',
            size === 'lg' ? 'max-w-3xl' : 'max-w-lg',
          )}
        >
          <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
            <div>
              <RadixDialog.Title className="text-md font-semibold">{title}</RadixDialog.Title>
              {description ? (
                <RadixDialog.Description className="mt-1 text-sm text-muted">{description}</RadixDialog.Description>
              ) : (
                <RadixDialog.Description className="sr-only">{title}</RadixDialog.Description>
              )}
            </div>
            <RadixDialog.Close asChild>
              <IconButton label="닫기" size="sm" icon={<X aria-hidden className="size-4" />} />
            </RadixDialog.Close>
          </div>
          <div className="overflow-y-auto px-5 py-4">{children}</div>
          {footer && <div className="flex flex-wrap justify-end gap-2 border-t border-line px-5 py-3">{footer}</div>}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

/**
 * 위험 작업 확인. 확인 버튼은 작업 이름을 그대로 쓴다(예: "보관", "지출 허용"). 처리 중에는 닫기·중복 실행을 막는다.
 */
export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  tone = 'danger',
  busy = false,
  confirmDisabled = false,
  cancelDisabled = false,
  onConfirm,
  onCancel,
  children,
}: {
  open: boolean
  title: string
  confirmLabel: string
  tone?: 'danger' | 'risk' | 'primary'
  busy?: boolean
  confirmDisabled?: boolean
  cancelDisabled?: boolean
  onConfirm: () => void
  onCancel: () => void
  children?: ReactNode
}) {
  return (
    <RadixDialog.Root
      open={open}
      onOpenChange={(next) => {
        if (!next && !busy && !cancelDisabled) onCancel()
      }}
    >
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/45" />
        <RadixDialog.Content
          role="alertdialog"
          className="fixed top-1/2 left-1/2 z-50 w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border border-line bg-surface p-5 shadow-overlay"
        >
          <RadixDialog.Title className="text-md font-semibold">{title}</RadixDialog.Title>
          <RadixDialog.Description asChild>
            <div className="mt-2 grid gap-3 text-base text-text">{children}</div>
          </RadixDialog.Description>
          <div className="mt-5 flex flex-wrap justify-end gap-2">
            <Button onClick={onCancel} disabled={busy || cancelDisabled}>
              취소
            </Button>
            <Button
              variant={tone === 'primary' ? 'primary' : tone}
              onClick={onConfirm}
              loading={busy}
              disabled={confirmDisabled}
            >
              {confirmLabel}
            </Button>
          </div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

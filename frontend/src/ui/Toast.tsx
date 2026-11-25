import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import { CheckCircle2, XCircle, X } from 'lucide-react'
import { ToastContext, type ToastApi, type ToastMessage } from './toast-context'
import { IconButton } from './Button'

const DURATION_MS = 5000

/** 토스트 영역(aria-live). 화면 오른쪽 아래, 5초 후 자동으로 닫힘. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [messages, setMessages] = useState<ToastMessage[]>([])
  const sequence = useRef(0)
  const dismiss = useCallback((id: number) => setMessages((current) => current.filter((m) => m.id !== id)), [])
  const push = useCallback(
    (tone: ToastMessage['tone'], text: string) => {
      const id = ++sequence.current
      setMessages((current) => [...current.slice(-2), { id, tone, text }])
      window.setTimeout(() => dismiss(id), DURATION_MS)
    },
    [dismiss],
  )
  const api = useMemo<ToastApi>(() => ({ success: (t) => push('success', t), error: (t) => push('error', t) }), [push])
  return (
    <ToastContext.Provider value={api}>
      {children}
      <div aria-live="polite" role="status" className="pointer-events-none fixed right-4 bottom-4 z-[60] grid w-80 max-w-[calc(100vw-2rem)] gap-2">
        {messages.map((message) => (
          <div
            key={message.id}
            className="pointer-events-auto flex items-start gap-2.5 rounded-lg border border-line bg-surface px-3.5 py-3 shadow-overlay"
          >
            {message.tone === 'success' ? (
              <CheckCircle2 aria-hidden className="mt-0.5 size-4 shrink-0 text-success" />
            ) : (
              <XCircle aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
            )}
            <p className="flex-1 text-base">{message.text}</p>
            <IconButton label="알림 닫기" size="sm" className="-my-1 -mr-1.5 size-7" onClick={() => dismiss(message.id)} icon={<X aria-hidden className="size-3.5" />} />
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

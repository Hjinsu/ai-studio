import type { ReactNode } from 'react'
import * as RadixTabs from '@radix-ui/react-tabs'
import * as RadixTooltip from '@radix-ui/react-tooltip'
import { cn } from '../lib/cn'

/** 툴팁: 보조 설명 전용(필수 정보는 화면에 직접 둔다). 접힌 사이드바 아이콘 이름 등에 쓴다. */
export function Tooltip({ content, children, side = 'right' }: { content: string; children: ReactNode; side?: 'top' | 'right' | 'bottom' | 'left' }) {
  return (
    <RadixTooltip.Root delayDuration={200}>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          side={side}
          sideOffset={6}
          className="z-50 rounded-md border border-line bg-surface px-2 py-1 text-xs text-text shadow-overlay"
        >
          {content}
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  )
}

export const TooltipProvider = RadixTooltip.Provider

/** 탭(Radix: 화살표 키 이동, aria 연결). 화면 안 영역 전환에 쓴다. */
export function Tabs({
  value,
  onValueChange,
  items,
  label,
}: {
  value: string
  onValueChange: (value: string) => void
  items: { value: string; label: string; content: ReactNode; badge?: ReactNode }[]
  label: string
}) {
  return (
    <RadixTabs.Root value={value} onValueChange={onValueChange}>
      <RadixTabs.List aria-label={label} className="flex gap-1 overflow-x-auto border-b border-line">
        {items.map((item) => (
          <RadixTabs.Trigger
            key={item.value}
            value={item.value}
            className={cn(
              '-mb-px inline-flex items-center gap-1.5 border-b-2 border-transparent px-3 py-2 text-base whitespace-nowrap text-muted',
              'hover:text-text data-[state=active]:border-primary data-[state=active]:font-medium data-[state=active]:text-text',
            )}
          >
            {item.label}
            {item.badge}
          </RadixTabs.Trigger>
        ))}
      </RadixTabs.List>
      {items.map((item) => (
        <RadixTabs.Content key={item.value} value={item.value} className="pt-5 focus-visible:outline-none">
          {item.content}
        </RadixTabs.Content>
      ))}
    </RadixTabs.Root>
  )
}

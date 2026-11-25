import { Film, Globe2, Tv } from 'lucide-react'
import type { BudgetPolicy } from '../../api/admin'
import { useChannelName } from '../../api/useChannels'
import { CONTENT_TYPE, SCOPE, text } from '../../lib/labels'
import { StatusBadge } from '../../ui/StatusBadge'

const SCOPE_ICON = { SYSTEM: Globe2, CHANNEL: Tv, CONTENT_TYPE: Film } as const

/** 정책 범위 배지: 아이콘 + 한국어 이름(시스템 전체/채널별/콘텐츠 유형)으로 시각 구분. */
export function ScopeBadge({ scope }: { scope: string }) {
  const Icon = SCOPE_ICON[scope as keyof typeof SCOPE_ICON] ?? Globe2
  return (
    <StatusBadge tone={scope === 'SYSTEM' ? 'primary' : 'neutral'} icon={<Icon aria-hidden className="size-3" />}>
      {text(SCOPE, scope)}
    </StatusBadge>
  )
}

/** 정책 대상: 채널명 또는 콘텐츠 유형(시스템 공통). */
export function PolicyTarget({ policy }: { policy: Pick<BudgetPolicy, 'scope' | 'channelId' | 'contentType'> }) {
  const channelName = useChannelName()
  if (policy.scope === 'CHANNEL') return <>{channelName(policy.channelId)}</>
  if (policy.scope === 'CONTENT_TYPE') return <>{text(CONTENT_TYPE, policy.contentType)} (시스템 공통)</>
  return <span className="text-muted">전체 채널</span>
}

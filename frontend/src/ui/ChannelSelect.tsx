import { forwardRef, type SelectHTMLAttributes } from 'react'
import { useAllChannels } from '../api/useChannels'
import { CHANNEL_STATUS } from '../lib/labels'
import { Select } from './Form'

/**
 * 채널 선택. 사용자는 채널명을 고르고 값은 channelId다(ID 직접 입력 금지).
 * excludeArchived: 신규 정책 등 보관 채널을 대상으로 할 수 없는 곳. allLabel: "전체" 같은 빈 선택지 문구.
 */
export const ChannelSelect = forwardRef<
  HTMLSelectElement,
  Omit<SelectHTMLAttributes<HTMLSelectElement>, 'value' | 'onChange'> & {
    value: string
    onChange: (channelId: string) => void
    excludeArchived?: boolean
    allLabel?: string
  }
>(function ChannelSelect({ value, onChange, excludeArchived = false, allLabel, ...rest }, ref) {
  const channels = useAllChannels()
  const options = (channels.data ?? []).filter((channel) => !excludeArchived || channel.status !== 'ARCHIVED')
  return (
    <Select ref={ref} value={value} onChange={(e) => onChange(e.target.value)} disabled={channels.isPending} {...rest}>
      <option value="">{channels.isPending ? '채널 불러오는 중…' : (allLabel ?? '채널 선택')}</option>
      {channels.isError && <option disabled>채널 목록을 불러오지 못했습니다</option>}
      {options.map((channel) => (
        <option key={channel.id} value={channel.id}>
          {channel.name}
          {channel.status !== 'ACTIVE' ? ` (${CHANNEL_STATUS[channel.status]?.text ?? channel.status})` : ''}
        </option>
      ))}
    </Select>
  )
})

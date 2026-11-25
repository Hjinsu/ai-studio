import { useQuery } from '@tanstack/react-query'
import { listChannels, type Channel } from './admin'

const PAGE_SIZE = 100
const MAX_PAGES = 20

/**
 * 채널 전체 목록(선택 컴포넌트·이름 표시용). API 최대 페이지 크기(100)로 끝까지 조회한다(최대 2,000개).
 * 화면에는 채널명을 보여주고 API에는 기존 channelId를 넘긴다.
 */
export function useAllChannels() {
  return useQuery({
    queryKey: ['channels', 'all'],
    queryFn: async (): Promise<Channel[]> => {
      const all: Channel[] = []
      for (let page = 0; page < MAX_PAGES; page++) {
        const result = await listChannels({ page, size: PAGE_SIZE })
        all.push(...result.items)
        if (page + 1 >= result.totalPages) {
          break
        }
      }
      return all
    },
    staleTime: 30_000,
  })
}

/** channelId → 채널명. 목록을 아직 못 받았거나 없는 ID면 "채널 #id". */
export function useChannelName(): (id: string | null | undefined) => string {
  const channels = useAllChannels()
  return (id) => {
    if (!id) return '—'
    return channels.data?.find((channel) => channel.id === id)?.name ?? `채널 #${id}`
  }
}

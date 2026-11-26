import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { getProductionMedia, isTerminal, mediaKey, type ProductionMedia, type ProductionRequestDetail } from '../api/production'

const POLL_MS = 5_000

/** 미디어 목록 조회(상세 화면의 안내 문구와 섹션이 같은 캐시를 쓴다). 완료 직후 한 번 더 읽어 마지막 확정 파일을 반영한다. */
export function useProductionMedia(request: ProductionRequestDetail) {
  const terminal = isTerminal(request.status)
  return useQuery({
    queryKey: [...mediaKey(request.id), terminal],
    queryFn: () => getProductionMedia(request.id),
    refetchInterval: terminal ? false : POLL_MS,
    retry: (failures, error) => !(error instanceof ApiError && error.status === 404) && failures < 2,
  })
}

export function hasFinalVideo(media: ProductionMedia | undefined): boolean {
  return media?.assets.some((asset) => asset.role === 'MP4') ?? false
}

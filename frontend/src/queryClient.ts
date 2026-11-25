import { QueryCache, QueryClient } from '@tanstack/react-query'
import { isUnauthorized } from './api/http'

export const ME_KEY = ['auth', 'me'] as const

/**
 * 서버 상태 클라이언트. 어떤 조회든 401이면(세션 만료·비밀번호 재설정으로 무효화) 현재 사용자 조회를 다시 해
 * 로그인 화면으로 돌아간다. 401·403·4xx는 재시도하지 않는다.
 */
export function createQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (isUnauthorized(error) && query.queryKey[0] !== ME_KEY[0]) {
          void client.invalidateQueries({ queryKey: ME_KEY })
        }
      },
    }),
    defaultOptions: {
      queries: {
        retry: (failureCount, error) =>
          !(error instanceof Error && 'status' in error && typeof error.status === 'number' && error.status < 500)
          && failureCount < 2,
        refetchOnWindowFocus: false,
      },
      mutations: { retry: false },
    },
  })
  return client
}

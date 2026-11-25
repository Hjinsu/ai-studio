import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { advanceSession, currentSession, isCurrentUnauthorized, isUnauthorized, SessionChangedError } from './api/http'
import { markSessionExpired } from './lib/session'

export const ME_KEY = ['auth', 'me'] as const

/** 로그인·로그아웃처럼 401이 세션 만료가 아닌 폼 오류인 변경 요청에 붙인다. */
export const AUTH_FLOW_META = { authFlow: true } as const

const isMe = (key: readonly unknown[]) => key[0] === ME_KEY[0]

/**
 * 서버 상태 클라이언트. 로그인된 상태에서 조회 또는 변경 요청이 401이면(세션 만료·비밀번호 재설정으로 무효화) 세션 만료로
 * 처리한다(UI-RV-02): 만료 안내 표시, 메모리 CSRF 토큰 폐기, 진행 중 조회 취소, 보호 데이터·변경 상태 제거, 현재 사용자
 * 재조회(401 → 로그인 화면). 여러 401이 동시에 와도 한 번만 처리한다. 실패한 변경 요청은 재로그인 후 자동 재실행하지 않는다.
 * 로그인 자격 증명 오류(AUTH_FLOW_META)는 폼 오류로 남긴다. 401·403·4xx는 재시도하지 않는다.
 */
export function createQueryClient(): QueryClient {
  let expiring = false
  // 이미 로그아웃 상태(로그인 상태 조회가 401)라면 늦게 도착한 401로 만료 처리를 반복하지 않는다
  const signedOut = () => {
    const me = client.getQueryState(ME_KEY)
    return me?.status === 'error' && me.data === undefined && isUnauthorized(me.error)
  }
  const expireSession = () => {
    if (expiring || signedOut()) {
      return
    }
    expiring = true
    markSessionExpired()
    advanceSession()
    const generation = currentSession()
    const protectedQuery = { predicate: (query: { queryKey: readonly unknown[] }) => !isMe(query.queryKey) }
    void client.cancelQueries(protectedQuery)
    client.getMutationCache().clear()
    // 로그인 상태를 먼저 초기화해(App이 보호 화면을 즉시 내림) 보호 화면이 쿼리를 다시 만들지 못하게 한 뒤 다시 확인한다
    void client.resetQueries({ queryKey: ME_KEY }).finally(() => {
      if (generation === currentSession()) client.removeQueries(protectedQuery)
      expiring = false
    })
    client.removeQueries(protectedQuery)
  }
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (isCurrentUnauthorized(error) && (!isMe(query.queryKey) || query.state.data !== undefined)) {
          expireSession()
        }
      },
    }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (isCurrentUnauthorized(error) && !mutation.meta?.authFlow) {
          expireSession()
        }
      },
    }),
    defaultOptions: {
      queries: {
        retry: (failureCount, error) =>
          !(error instanceof SessionChangedError)
          && !(error instanceof Error && 'status' in error && typeof error.status === 'number' && error.status < 500)
          && failureCount < 2,
        refetchOnWindowFocus: false,
      },
      mutations: { retry: false },
    },
  })
  return client
}

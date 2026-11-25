import { useQuery } from '@tanstack/react-query'
import { RouterProvider, type DataRouter } from 'react-router'
import { currentUser } from './api/admin'
import { isUnauthorized } from './api/http'
import { ME_KEY } from './queryClient'
import { ErrorAlert, Loading } from './ui/components'
import { LoginPage } from './pages/LoginPage'

/** 로그인 여부에 따라 로그인 화면 또는 관리자 화면(라우터)을 보여준다(ADR-037). */
export function App({ router }: { router: DataRouter }) {
  const me = useQuery({ queryKey: ME_KEY, queryFn: currentUser, retry: false })
  if (me.isPending) {
    return <Loading />
  }
  if (me.isError) {
    if (isUnauthorized(me.error)) {
      return <LoginPage />
    }
    return (
      <main className="centered">
        <ErrorAlert error={me.error} />
        <button type="button" onClick={() => void me.refetch()}>
          다시 시도
        </button>
      </main>
    )
  }
  return <RouterProvider router={router} />
}

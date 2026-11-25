import { useMutation, useQueryClient } from '@tanstack/react-query'
import { NavLink, Outlet } from 'react-router'
import { logout } from './api/admin'
import { ME_KEY } from './queryClient'
import { ErrorAlert } from './ui/components'

const MENU: { to: string; label: string }[] = [
  { to: '/', label: '대시보드' },
  { to: '/channels', label: '채널' },
  { to: '/budget/policies', label: '예산 정책' },
  { to: '/budget/usage', label: '예산 사용량' },
  { to: '/budget/control', label: '지출 제어·정합성' },
  { to: '/budget/reservations', label: '예약·정산 확인' },
  { to: '/cost-ledger', label: '비용 원장' },
  { to: '/exchange-rates', label: '환율' },
  { to: '/system', label: '서버 상태' },
]

export function Layout() {
  const queryClient = useQueryClient()
  const signOut = useMutation({
    mutationFn: logout,
    onSettled: async () => {
      // 세션 폐기 후 캐시를 비우고 현재 사용자 재조회 → 로그인 화면
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== ME_KEY[0] })
      await queryClient.invalidateQueries({ queryKey: ME_KEY })
    },
  })
  return (
    <div className="shell">
      <nav aria-label="주 메뉴" className="sidebar">
        <strong className="brand">AI Studio</strong>
        <ul>
          {MENU.map((item) => (
            <li key={item.to}>
              <NavLink to={item.to} end={item.to === '/'}>
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
        <button type="button" onClick={() => signOut.mutate()} disabled={signOut.isPending}>
          로그아웃
        </button>
        <ErrorAlert error={signOut.error} />
      </nav>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}

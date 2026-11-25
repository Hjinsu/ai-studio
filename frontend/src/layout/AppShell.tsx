import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import {
  ChevronRight,
  LogOut,
  Menu,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Sun,
  UserRound,
  X,
} from 'lucide-react'
import { currentUser, logout } from '../api/admin'
import { fetchHealth } from '../api/health'
import { ME_KEY } from '../queryClient'
import { cn } from '../lib/cn'
import { useTheme } from '../lib/theme'
import { Button, IconButton } from '../ui/Button'
import { Tooltip, TooltipProvider } from '../ui/Overlay'
import { useToast } from '../ui/toast-context'
import { NAV, locate } from './nav'

const COLLAPSE_KEY = 'ai-studio.sidebar-collapsed'

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSE_KEY) === 'true'
  } catch {
    return false
  }
}

/** AppShell: 사이드바(그룹 메뉴·접기·테마·로그아웃) + 헤더(경로·서버 상태·계정) + 콘텐츠(최대 1280px). */
export function AppShell() {
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const [mobileOpen, setMobileOpen] = useState(false)

  const toggleCollapsed = () => {
    setCollapsed((current) => {
      try {
        localStorage.setItem(COLLAPSE_KEY, String(!current))
      } catch {
        // 저장 불가 시 현재 화면에만 적용
      }
      return !current
    })
  }

  return (
    <TooltipProvider>
      <div className="flex min-h-screen bg-bg text-text">
        <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-[70] focus:rounded-md focus:bg-surface focus:px-3 focus:py-2">
          본문으로 건너뛰기
        </a>
        {/* 데스크톱 사이드바 */}
        <div className={cn('hidden shrink-0 border-r border-line bg-sidebar md:block', collapsed ? 'w-16' : 'w-60')}>
          <aside className="sticky top-0 flex h-screen flex-col">
            <Sidebar collapsed={collapsed} onToggleCollapsed={toggleCollapsed} />
          </aside>
        </div>
        {/* 모바일 드로어 */}
        {mobileOpen && (
          <div className="fixed inset-0 z-40 md:hidden">
            <div className="absolute inset-0 bg-black/45" onClick={() => setMobileOpen(false)} aria-hidden />
            <aside
              role="dialog"
              aria-modal="true"
              aria-label="주 메뉴"
              className="absolute inset-y-0 left-0 flex w-64 flex-col border-r border-line bg-sidebar"
              onKeyDown={(event) => event.key === 'Escape' && setMobileOpen(false)}
            >
              <Sidebar collapsed={false} onClose={() => setMobileOpen(false)} />
            </aside>
          </div>
        )}
        <div className="flex min-w-0 flex-1 flex-col">
          <Header onOpenMenu={() => setMobileOpen(true)} />
          <main id="main" className="mx-auto w-full max-w-[80rem] flex-1 px-4 py-6 sm:px-6 lg:px-8">
            <Outlet />
          </main>
        </div>
      </div>
    </TooltipProvider>
  )
}

function Sidebar({
  collapsed,
  onToggleCollapsed,
  onClose,
}: {
  collapsed: boolean
  onToggleCollapsed?: () => void
  onClose?: () => void
}) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [theme, setTheme] = useTheme()
  const signOut = useMutation({
    mutationFn: logout,
    onSettled: async (_data, error) => {
      if (error) toast.error('로그아웃 요청이 실패했습니다. 세션을 정리하고 로그인 화면으로 이동합니다.')
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== ME_KEY[0] })
      await queryClient.invalidateQueries({ queryKey: ME_KEY })
    },
  })
  const nextTheme = theme === 'dark' ? 'light' : 'dark'
  const themeLabel = nextTheme === 'dark' ? '다크 모드로 전환' : '라이트 모드로 전환'

  return (
    <div className="flex h-full flex-col">
      <div className={cn('flex h-14 items-center gap-2 border-b border-line px-4', collapsed && 'justify-center px-0')}>
        <Link to="/" className="flex items-center gap-2 font-semibold tracking-tight" aria-label="AI Studio 대시보드">
          <span aria-hidden className="grid size-7 place-items-center rounded-md bg-primary text-sm font-bold text-primary-fg">
            AI
          </span>
          {!collapsed && <span className="text-md">AI Studio</span>}
        </Link>
        {onClose && (
          <IconButton label="메뉴 닫기" className="ml-auto" onClick={onClose} icon={<X aria-hidden className="size-4" />} />
        )}
      </div>
      <nav aria-label="주 메뉴" className="flex-1 overflow-y-auto px-2 py-3">
        {NAV.map((group) => (
          <div key={group.label} className="mb-4">
            {!collapsed ? (
              <p className="px-2.5 pb-1.5 text-xs font-medium text-muted">{group.label}</p>
            ) : (
              <div aria-hidden className="mx-3 mb-2 border-t border-line" />
            )}
            <ul className="grid gap-0.5">
              {group.items.map((item) => {
                const link = (
                  <NavLink
                    to={item.to}
                    end={item.to === '/'}
                    onClick={onClose}
                    aria-label={collapsed ? item.label : undefined}
                    className={({ isActive }) =>
                      cn(
                        'relative flex h-9 items-center gap-2.5 rounded-md px-2.5 text-base',
                        collapsed && 'justify-center px-0',
                        isActive
                          ? 'bg-primary-soft font-medium text-primary before:absolute before:inset-y-1.5 before:left-0 before:w-0.5 before:rounded-full before:bg-primary'
                          : 'text-text hover:bg-subtle',
                      )
                    }
                  >
                    <item.icon aria-hidden className="size-4 shrink-0" />
                    {!collapsed && <span className="truncate">{item.label}</span>}
                  </NavLink>
                )
                return <li key={item.to}>{collapsed ? <Tooltip content={item.label}>{link}</Tooltip> : link}</li>
              })}
            </ul>
          </div>
        ))}
      </nav>
      <div className={cn('grid gap-1.5 border-t border-line p-2', collapsed && 'justify-items-center')}>
        {collapsed ? (
          <>
            <Tooltip content={themeLabel}>
              <IconButton label={themeLabel} onClick={() => setTheme(nextTheme)} icon={nextTheme === 'dark' ? <Moon aria-hidden className="size-4" /> : <Sun aria-hidden className="size-4" />} />
            </Tooltip>
            <Tooltip content="사이드바 펼치기">
              <IconButton label="사이드바 펼치기" onClick={onToggleCollapsed} icon={<PanelLeftOpen aria-hidden className="size-4" />} />
            </Tooltip>
            <Tooltip content="로그아웃">
              <IconButton label="로그아웃" variant="secondary" onClick={() => signOut.mutate()} loading={signOut.isPending} icon={<LogOut aria-hidden className="size-4" />} />
            </Tooltip>
          </>
        ) : (
          <>
            <div className="flex gap-1">
              <Button variant="ghost" className="flex-1 justify-start" onClick={() => setTheme(nextTheme)} icon={nextTheme === 'dark' ? <Moon aria-hidden className="size-4" /> : <Sun aria-hidden className="size-4" />}>
                {nextTheme === 'dark' ? '다크 모드' : '라이트 모드'}
              </Button>
              {onToggleCollapsed && (
                <IconButton label="사이드바 접기" onClick={onToggleCollapsed} icon={<PanelLeftClose aria-hidden className="size-4" />} />
              )}
            </div>
            <Button variant="secondary" className="justify-start" onClick={() => signOut.mutate()} loading={signOut.isPending} icon={<LogOut aria-hidden className="size-4" />}>
              로그아웃
            </Button>
          </>
        )}
      </div>
    </div>
  )
}

type ServerState = 'up' | 'checking' | 'down'

function Header({ onOpenMenu }: { onOpenMenu: () => void }) {
  const location = useLocation()
  const place = locate(location.pathname)
  const me = useQuery({ queryKey: ME_KEY, queryFn: currentUser, retry: false })
  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth, refetchInterval: 30_000, retry: false })
  const server: ServerState = health.isPending ? 'checking' : health.data?.status === 'UP' ? 'up' : 'down'
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-line bg-surface/95 px-4 backdrop-blur sm:px-6">
      <IconButton label="메뉴 열기" className="md:hidden" onClick={onOpenMenu} icon={<Menu aria-hidden className="size-4" />} />
      <nav aria-label="현재 위치" className="min-w-0 flex-1">
        <ol className="flex min-w-0 items-center gap-1.5 text-base">
          {place && (
            <>
              <li className="hidden text-muted sm:block">{place.group.label}</li>
              <li aria-hidden className="hidden text-muted sm:block">
                <ChevronRight className="size-3.5" />
              </li>
              <li className={cn('truncate', place.detail ? 'text-muted' : 'font-medium')}>
                {place.detail ? <Link to={place.item.to} className="hover:text-text">{place.item.label}</Link> : place.item.label}
              </li>
              {place.detail && (
                <>
                  <li aria-hidden className="text-muted">
                    <ChevronRight className="size-3.5" />
                  </li>
                  <li className="truncate font-medium" aria-current="page">상세</li>
                </>
              )}
            </>
          )}
        </ol>
      </nav>
      <Link
        to="/system"
        className="flex items-center gap-1.5 rounded-full border border-line px-2.5 py-1 text-sm hover:bg-subtle"
        aria-label={`서버 상태: ${server === 'up' ? '정상' : server === 'checking' ? '확인 중' : '연결 실패'}`}
      >
        <span
          aria-hidden
          className={cn(
            'size-2 rounded-full',
            server === 'up' && 'bg-success',
            server === 'checking' && 'bg-muted',
            server === 'down' && 'bg-danger',
          )}
        />
        <span className="hidden sm:inline">
          {server === 'up' ? '서버 정상' : server === 'checking' ? '확인 중' : '연결 실패'}
        </span>
      </Link>
      <span className="hidden items-center gap-1.5 text-sm text-muted sm:flex" title="로그인한 관리자">
        <UserRound aria-hidden className="size-4" />
        <span className="max-w-32 truncate text-text">{me.data?.login ?? '—'}</span>
      </span>
    </header>
  )
}

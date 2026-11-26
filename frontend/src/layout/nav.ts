import {
  Activity,
  ArrowLeftRight,
  BookOpenText,
  Clapperboard,
  Gauge,
  History,
  LayoutDashboard,
  Receipt,
  ShieldAlert,
  Tv,
  WalletCards,
  type LucideIcon,
} from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

/**
 * 사이드바 메뉴. 그룹 단위로 추가한다(T03 이후: 예) "콘텐츠 제작" 그룹에 제작·작업·검수, "연동" 그룹에 외부 서비스).
 * 경로는 routes.tsx와 같아야 한다.
 */
export const NAV: NavGroup[] = [
  { label: '개요', items: [{ to: '/', label: '대시보드', icon: LayoutDashboard }] },
  { label: '채널 관리', items: [{ to: '/channels', label: '채널 관리', icon: Tv }] },
  {
    label: '콘텐츠 제작',
    items: [
      { to: '/production', label: '제작 시작', icon: Clapperboard },
      { to: '/production/requests', label: '제작 이력', icon: History },
    ],
  },
  {
    label: '비용·예산',
    items: [
      { to: '/budget/policies', label: '예산 정책', icon: WalletCards },
      { to: '/budget/usage', label: '예산 사용량', icon: Gauge },
      { to: '/budget/control', label: '지출 제어', icon: ShieldAlert },
      { to: '/budget/reservations', label: '예약·정산', icon: Receipt },
      { to: '/cost-ledger', label: '비용 원장', icon: BookOpenText },
      { to: '/exchange-rates', label: '환율 관리', icon: ArrowLeftRight },
    ],
  },
  { label: '시스템', items: [{ to: '/system', label: '서버 상태', icon: Activity }] },
]

/** 다른 메뉴가 이 경로 아래에 있으면(예: /production 아래 /production/requests) 정확히 일치할 때만 활성으로 표시한다. */
export function hasNestedItem(to: string): boolean {
  return to !== '/' && NAV.some((group) => group.items.some((item) => item.to.startsWith(`${to}/`)))
}

/** 현재 경로의 메뉴(가장 긴 접두 일치)와 상세 화면 여부. */
export function locate(pathname: string): { group: NavGroup; item: NavItem; detail: boolean } | null {
  let best: { group: NavGroup; item: NavItem } | null = null
  for (const group of NAV) {
    for (const item of group.items) {
      const matches = item.to === '/' ? pathname === '/' : pathname === item.to || pathname.startsWith(`${item.to}/`)
      if (matches && (!best || item.to.length > best.item.to.length)) {
        best = { group, item }
      }
    }
  }
  return best ? { ...best, detail: pathname !== best.item.to } : null
}

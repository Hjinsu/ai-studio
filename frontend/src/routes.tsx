import type { RouteObject } from 'react-router'
import { AppShell } from './layout/AppShell'
import { LoadingSkeleton } from './ui/States'

/**
 * T02 관리자 화면 (ADR-035). 제작·검수·게시 화면은 후속 단계. 메뉴는 layout/nav.ts와 함께 추가한다.
 * 화면별 코드 분할: React Router route.lazy로 화면 모듈을 필요할 때 불러온다.
 * 첫 진입 시 lazy 모듈을 기다리는 동안 hydrateFallbackElement를 보여준다(없으면 React Router가 콘솔 경고, UI-RV-A03).
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    hydrateFallbackElement: (
      <main className="grid min-h-screen place-items-center bg-bg px-4">
        <LoadingSkeleton rows={2} className="w-64" />
      </main>
    ),
    children: [
      { index: true, lazy: () => import('./pages/DashboardPage').then((m) => ({ Component: m.DashboardPage })) },
      { path: 'channels', lazy: () => import('./pages/ChannelsPage').then((m) => ({ Component: m.ChannelsPage })) },
      { path: 'channels/:channelId', lazy: () => import('./pages/ChannelDetailPage').then((m) => ({ Component: m.ChannelDetailPage })) },
      { path: 'production', lazy: () => import('./pages/ProductionRequestStartPage').then((m) => ({ Component: m.ProductionRequestStartPage })) },
      { path: 'production/requests', lazy: () => import('./pages/ProductionRequestsPage').then((m) => ({ Component: m.ProductionRequestsPage })) },
      { path: 'production/:requestId', lazy: () => import('./pages/ProductionRequestDetailPage').then((m) => ({ Component: m.ProductionRequestDetailPage })) },
      { path: 'budget/policies', lazy: () => import('./pages/PoliciesPage').then((m) => ({ Component: m.PoliciesPage })) },
      { path: 'budget/policies/:policyId', lazy: () => import('./pages/PolicyDetailPage').then((m) => ({ Component: m.PolicyDetailPage })) },
      { path: 'budget/usage', lazy: () => import('./pages/UsagePage').then((m) => ({ Component: m.UsagePage })) },
      { path: 'budget/control', lazy: () => import('./pages/SpendingControlPage').then((m) => ({ Component: m.SpendingControlPage })) },
      { path: 'budget/reservations', lazy: () => import('./pages/ReservationsPage').then((m) => ({ Component: m.ReservationsPage })) },
      { path: 'budget/reservations/:reservationId', lazy: () => import('./pages/ReservationDetailPage').then((m) => ({ Component: m.ReservationDetailPage })) },
      { path: 'cost-ledger', lazy: () => import('./pages/CostLedgerPage').then((m) => ({ Component: m.CostLedgerPage })) },
      { path: 'exchange-rates', lazy: () => import('./pages/ExchangeRatesPage').then((m) => ({ Component: m.ExchangeRatesPage })) },
      { path: 'system', lazy: () => import('./pages/HealthPage').then((m) => ({ Component: m.HealthPage })) },
      { path: '*', lazy: () => import('./pages/NotFoundPage').then((m) => ({ Component: m.NotFoundPage })) },
    ],
  },
]

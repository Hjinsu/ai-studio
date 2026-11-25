import type { RouteObject } from 'react-router'
import { Layout } from './Layout'
import { ChannelDetailPage } from './pages/ChannelDetailPage'
import { ChannelsPage } from './pages/ChannelsPage'
import { CostLedgerPage } from './pages/CostLedgerPage'
import { DashboardPage } from './pages/DashboardPage'
import { ExchangeRatesPage } from './pages/ExchangeRatesPage'
import { HealthPage } from './pages/HealthPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { PoliciesPage } from './pages/PoliciesPage'
import { PolicyDetailPage } from './pages/PolicyDetailPage'
import { ReservationDetailPage } from './pages/ReservationDetailPage'
import { ReservationsPage } from './pages/ReservationsPage'
import { SpendingControlPage } from './pages/SpendingControlPage'
import { UsagePage } from './pages/UsagePage'

/** T02 관리자 화면 (ADR-035). 제작·검수·게시 화면은 후속 단계. */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'channels', element: <ChannelsPage /> },
      { path: 'channels/:channelId', element: <ChannelDetailPage /> },
      { path: 'budget/policies', element: <PoliciesPage /> },
      { path: 'budget/policies/:policyId', element: <PolicyDetailPage /> },
      { path: 'budget/usage', element: <UsagePage /> },
      { path: 'budget/control', element: <SpendingControlPage /> },
      { path: 'budget/reservations', element: <ReservationsPage /> },
      { path: 'budget/reservations/:reservationId', element: <ReservationDetailPage /> },
      { path: 'cost-ledger', element: <CostLedgerPage /> },
      { path: 'exchange-rates', element: <ExchangeRatesPage /> },
      { path: 'system', element: <HealthPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

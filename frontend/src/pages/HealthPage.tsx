import { useQuery } from '@tanstack/react-query'
import { fetchHealth } from '../api/health'

const REFRESH_INTERVAL_MS = 30_000

// 화면 시간은 사용자 브라우저 시간대로 표시한다 (AGENTS: UI는 사용자 시간대)
function formatLocalTime(epochMs: number): string {
  const formatted = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(epochMs)
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
  return `${formatted} (${timeZone})`
}

export function HealthPage() {
  const health = useQuery({
    queryKey: ['health'],
    queryFn: fetchHealth,
    refetchInterval: REFRESH_INTERVAL_MS,
    retry: false,
  })

  return (
    <section aria-labelledby="health-heading">
      <h2 id="health-heading">서버 상태</h2>
      {health.isPending && <p role="status">확인 중…</p>}
      {health.isError && <p role="alert">서버에 연결할 수 없습니다.</p>}
      {health.isSuccess && (
        <dl>
          <dt>상태</dt>
          <dd data-testid="health-status">{health.data.status}</dd>
          <dt>마지막 확인</dt>
          <dd>{formatLocalTime(health.dataUpdatedAt)}</dd>
        </dl>
      )}
    </section>
  )
}

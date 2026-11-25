import { useQuery } from '@tanstack/react-query'
import { CircleCheck, CircleX, Loader2, RefreshCw } from 'lucide-react'
import { fetchHealth } from '../api/health'
import { browserTimeZone, formatDateTime } from '../lib/time'
import { cn } from '../lib/cn'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { PageHeader } from '../ui/PageHeader'

const REFRESH_INTERVAL_MS = 30_000

/** 서버 상태: Actuator health(UP/DOWN)만 표시한다(ADR-008). 내부 구성·DB 정보는 노출하지 않는다. */
export function HealthPage() {
  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth, refetchInterval: REFRESH_INTERVAL_MS, retry: false })
  const state = health.isPending ? 'checking' : health.isError ? 'down' : health.data.status === 'UP' ? 'up' : 'down'
  const view = {
    up: { text: '정상', Icon: CircleCheck, tone: 'text-success', detail: '백엔드 서버가 응답하고 있습니다.' },
    checking: { text: '확인 중', Icon: Loader2, tone: 'text-muted', detail: '서버 상태를 확인하고 있습니다.' },
    down: { text: '연결 실패', Icon: CircleX, tone: 'text-danger', detail: health.isError ? '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인하세요.' : '서버가 정상 상태가 아니라고 응답했습니다.' },
  }[state]
  return (
    <>
      <PageHeader title="서버 상태" description="백엔드 서버의 응답 여부를 30초마다 확인합니다." />
      <Card
        actions={<Button size="sm" icon={<RefreshCw aria-hidden className="size-3.5" />} onClick={() => void health.refetch()} loading={health.isFetching}>다시 확인</Button>}
        title="백엔드"
      >
        <div className="flex items-center gap-3" role="status" aria-live="polite">
          <view.Icon aria-hidden className={cn('size-8', view.tone, state === 'checking' && 'animate-spin')} />
          <div>
            <p className={cn('text-lg font-semibold', view.tone)} data-testid="health-status">{view.text}</p>
            <p className="text-sm text-muted">{view.detail}</p>
          </div>
        </div>
        {health.dataUpdatedAt > 0 && (
          <p className="mt-4 text-xs text-muted">
            마지막 확인 {formatDateTime(new Date(health.dataUpdatedAt).toISOString(), undefined, true)} ({browserTimeZone()})
          </p>
        )}
      </Card>
    </>
  )
}

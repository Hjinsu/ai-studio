import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Info } from 'lucide-react'
import { budgetUsage, type BudgetBucket } from '../api/admin'
import { useChannelName } from '../api/useChannels'
import { minDecimal } from '../lib/decimal'
import { PERIOD, SCOPE, text } from '../lib/labels'
import { Card } from '../ui/Card'
import { ChannelSelect } from '../ui/ChannelSelect'
import { DateTime, Money } from '../ui/Display'
import { FormField } from '../ui/Form'
import { BudgetMeter } from '../ui/Meter'
import { Tabs } from '../ui/Overlay'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, InlineNotice, QueryView } from '../ui/States'

/**
 * 예산 사용량(현재 기간). 시스템 예산과 채널 예산을 탭으로 나눠 보여준다. 각 정책의 bucket은 서로 합산하지 않는다.
 * 표시된 잔여는 참고값이며 예약 가능 여부는 서버의 예산 검사가 결정한다.
 */
export function UsagePage() {
  const [tab, setTab] = useState('system')
  return (
    <>
      <PageHeader title="예산 사용량" description="지금 진행 중인 기간의 한도, 실제 지출, 예약 금액, 잔여 예산입니다." />
      <Tabs
        label="예산 범위"
        value={tab}
        onValueChange={setTab}
        items={[
          { value: 'system', label: '시스템 예산', content: <SystemUsage /> },
          { value: 'channel', label: '채널 예산', content: <ChannelUsage /> },
        ]}
      />
    </>
  )
}

function SystemUsage() {
  const usage = useQuery({ queryKey: ['usage', ''], queryFn: () => budgetUsage() })
  return (
    <QueryView query={usage}>
      {(data) => {
        const system = data.buckets.filter((b) => b.scope === 'SYSTEM')
        const other = data.buckets.filter((b) => b.scope !== 'SYSTEM')
        return (
          <div className="grid gap-5">
            {system.length === 0 ? (
              <EmptyState title="활성 시스템 예산 정책이 없습니다" description="시스템 전체 일간·월간 정책이 없으면 모든 비용 예약이 차단됩니다." />
            ) : (
              <div className="grid gap-4 lg:grid-cols-2">
                {system.map((bucket) => <BucketCard key={bucket.policyId} bucket={bucket} />)}
              </div>
            )}
            {other.length > 0 && (
              <>
                <h2 className="text-md font-semibold">콘텐츠 유형 예산</h2>
                <div className="grid gap-4 lg:grid-cols-2">
                  {other.map((bucket) => <BucketCard key={bucket.policyId} bucket={bucket} />)}
                </div>
              </>
            )}
            <p className="text-xs text-muted">기준 시각 <DateTime value={data.at} /></p>
          </div>
        )
      }}
    </QueryView>
  )
}

function ChannelUsage() {
  const [channelId, setChannelId] = useState('')
  const channelName = useChannelName()
  const usage = useQuery({ queryKey: ['usage', channelId], queryFn: () => budgetUsage(channelId), enabled: channelId !== '' })
  return (
    <div className="grid gap-5">
      <FormField label="채널" className="max-w-sm">
        <ChannelSelect value={channelId} onChange={setChannelId} />
      </FormField>
      {channelId === '' ? (
        <EmptyState title="채널을 선택하세요" description="채널 예산과 그 채널에 함께 적용되는 시스템 예산을 보여줍니다." />
      ) : (
        <QueryView query={usage}>
          {(data) => {
            const channelBuckets = data.buckets.filter((b) => b.scope === 'CHANNEL')
            const applicable = minDecimal(data.buckets.map((b) => b.remainingKrw))
            return (
              <div className="grid gap-5">
                {applicable !== null && (
                  <Card title="이 채널에서 지금 쓸 수 있는 금액(참고)">
                    <p className="num text-xl font-semibold">
                      <Money value={applicable} />
                    </p>
                    <InlineNotice icon={<Info className="size-4 text-muted" />}>
                      시스템 일간·월간과 채널 정책 중 <strong>가장 작은 잔여</strong>입니다. 콘텐츠 유형 한도, 지출 차단, 정합성 상태,
                      채널 상태, 환율 유효성은 포함하지 않았습니다. 실제 예약 가능 여부는 서버가 예약 시점에 판단합니다.
                    </InlineNotice>
                  </Card>
                )}
                {channelBuckets.length === 0 ? (
                  <EmptyState title={`${channelName(channelId)}에는 채널 예산 정책이 없습니다`} description="시스템 예산만 적용됩니다." />
                ) : (
                  <div className="grid gap-4 lg:grid-cols-2">
                    {channelBuckets.map((bucket) => <BucketCard key={bucket.policyId} bucket={bucket} />)}
                  </div>
                )}
              </div>
            )
          }}
        </QueryView>
      )}
    </div>
  )
}

function BucketCard({ bucket }: { bucket: BudgetBucket }) {
  const channelName = useChannelName()
  const title = `${text(SCOPE, bucket.scope)} · ${text(PERIOD, bucket.periodType)}${bucket.channelId ? ` · ${channelName(bucket.channelId)}` : ''}`
  return (
    <Card>
      <BudgetMeter
        title={title}
        limitKrw={bucket.limitKrw}
        spentKrw={bucket.spentKrw}
        reservedKrw={bucket.reservedKrw}
        remainingKrw={bucket.remainingKrw}
        caption={bucket.id === null ? '이 기간에는 아직 예약이 없습니다.' : undefined}
      />
      <p className="mt-2 text-xs text-muted">
        기간 <DateTime value={bucket.periodStart} timeZone={bucket.timezone} showZone={false} /> ~ <DateTime value={bucket.periodEnd} timeZone={bucket.timezone} />
      </p>
    </Card>
  )
}

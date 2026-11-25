import { Link } from 'react-router'
import type { BudgetBucket } from '../api/admin'
import { Empty } from '../ui/components'
import { formatInstant, money } from '../ui/format'

/** 예산 bucket 표. 기간은 bucket 시간대로 표시, 금액은 문자열 그대로. 실제(spent)·예약(reserved) 구분. */
export function BucketTable({ buckets, empty }: { buckets: BudgetBucket[]; empty?: string }) {
  if (buckets.length === 0) {
    return <Empty>{empty}</Empty>
  }
  return (
    <table>
      <thead>
        <tr>
          <th scope="col">정책</th>
          <th scope="col">범위</th>
          <th scope="col">기간</th>
          <th scope="col">한도</th>
          <th scope="col">실제 지출</th>
          <th scope="col">예약</th>
          <th scope="col">잔여</th>
        </tr>
      </thead>
      <tbody>
        {buckets.map((bucket) => (
          <tr key={`${bucket.policyId}-${bucket.periodStart}`}>
            <td>
              <Link to={`/budget/policies/${bucket.policyId}`}>#{bucket.policyId}</Link>
            </td>
            <td>
              {bucket.scope} {bucket.periodType}
              {bucket.channelId && <> · 채널 #{bucket.channelId}</>}
            </td>
            <td>
              {formatInstant(bucket.periodStart, bucket.timezone)}
              <br />~ {formatInstant(bucket.periodEnd, bucket.timezone)}
            </td>
            <td>{money(bucket.limitKrw)}</td>
            <td>{money(bucket.spentKrw)}</td>
            <td>{money(bucket.reservedKrw)}</td>
            <td className={bucket.remainingKrw.startsWith('-') ? 'warn' : undefined}>{money(bucket.remainingKrw)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

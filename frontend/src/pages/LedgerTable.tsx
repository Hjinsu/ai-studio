import { Link } from 'react-router'
import type { CostLogEntry } from '../api/admin'
import { Empty } from '../ui/components'
import { formatInstant, money } from '../ui/format'

/** 원장 이벤트 표(append-only). 예약 증감(ΔR)과 비용 증감(ΔC)을 구분해 표시한다. */
export function LedgerTable({ entries }: { entries: CostLogEntry[] }) {
  if (entries.length === 0) {
    return <Empty>원장 이벤트가 없습니다.</Empty>
  }
  return (
    <table>
      <thead>
        <tr>
          <th scope="col">ID</th>
          <th scope="col">유형</th>
          <th scope="col">예약</th>
          <th scope="col">원본 금액</th>
          <th scope="col">환율</th>
          <th scope="col">예약 증감(KRW)</th>
          <th scope="col">비용 증감(KRW)</th>
          <th scope="col">비용 증감(원본)</th>
          <th scope="col">주체</th>
          <th scope="col">발생</th>
        </tr>
      </thead>
      <tbody>
        {entries.map((entry) => (
          <tr key={entry.id}>
            <td title={entry.eventKey}>#{entry.id}</td>
            <td>
              {entry.entryType}
              {entry.reasonCode && <> · {entry.reasonCode}</>}
            </td>
            <td>
              <Link to={`/budget/reservations/${entry.reservationId}`}>#{entry.reservationId}</Link>
            </td>
            <td>{money(entry.originalAmount, entry.originalCurrency)}</td>
            <td>{entry.fxRate}</td>
            <td>{entry.reserveDeltaKrw}</td>
            <td>{entry.costDeltaKrw}</td>
            <td>{entry.costDeltaOriginal}</td>
            <td>
              {entry.actorType}
              {entry.actorUserId && <> #{entry.actorUserId}</>}
            </td>
            <td>{formatInstant(entry.occurredAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

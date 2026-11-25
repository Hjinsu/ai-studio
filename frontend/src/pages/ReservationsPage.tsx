import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { listReservations } from '../api/admin'
import { Empty, Field, Pager, QueryView } from '../ui/components'
import { formatInstant, money } from '../ui/format'

/** 비용 예약 목록. RECONCILIATION_REQUIRED(외부 결과 불명)는 관리자 정산 확인 대상(자동 해제 없음). */
export function ReservationsPage() {
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const [page, setPage] = useState(0)
  const reservations = useQuery({
    queryKey: ['reservations', status, page],
    queryFn: () => listReservations({ status: status || undefined, page, size: 20 }),
    placeholderData: keepPreviousData,
  })
  return (
    <section aria-labelledby="reservations-heading">
      <h1 id="reservations-heading">예약·정산 확인</h1>
      <Field label="상태">
        <select
          value={status}
          onChange={(e) => {
            setPage(0)
            setParams(e.target.value ? { status: e.target.value } : {})
          }}
        >
          <option value="">전체</option>
          <option value="ACTIVE">ACTIVE</option>
          <option value="RECONCILIATION_REQUIRED">RECONCILIATION_REQUIRED(확인 필요)</option>
          <option value="SETTLED">SETTLED</option>
          <option value="RELEASED">RELEASED</option>
        </select>
      </Field>
      <QueryView query={reservations}>
        {(data) =>
          data.items.length === 0 ? (
            <Empty>예약이 없습니다.</Empty>
          ) : (
            <>
              <table>
                <thead>
                  <tr>
                    <th scope="col">ID</th>
                    <th scope="col">요청 키</th>
                    <th scope="col">용도</th>
                    <th scope="col">상태</th>
                    <th scope="col">예상</th>
                    <th scope="col">남은 예약</th>
                    <th scope="col">인식 비용</th>
                    <th scope="col">생성</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <Link to={`/budget/reservations/${r.id}`}>#{r.id}</Link>
                      </td>
                      <td>
                        <code>{r.requestKey}</code>
                      </td>
                      <td>
                        {r.purpose}
                        {r.channelId && <> · 채널 #{r.channelId}</>}
                      </td>
                      <td className={r.status === 'RECONCILIATION_REQUIRED' ? 'warn' : undefined}>{r.status}</td>
                      <td>
                        {money(r.estimateOriginal, r.originalCurrency)} ({money(r.estimateKrw)})
                      </td>
                      <td>{money(r.reservedRemainingKrw)}</td>
                      <td>
                        {money(r.recognizedCostKrw)} ({money(r.recognizedCostOriginal, r.originalCurrency)})
                      </td>
                      <td>{formatInstant(r.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
            </>
          )
        }
      </QueryView>
    </section>
  )
}

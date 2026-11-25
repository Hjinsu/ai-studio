import { useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createExchangeRate, listExchangeRates } from '../api/admin'
import { Empty, ErrorAlert, Field, Pager, QueryView } from '../ui/components'
import { formatInstant } from '../ui/format'

/**
 * 환율(ADR-027, 046): 관리자 수동 등록, 등록 후 불변. effectiveFrom은 now−5분 ~ now+7일, 유효기간 기본 72시간.
 * 최신 발효 환율이 만료되면 이전 환율로 대체하지 않고 외화 예약이 차단된다.
 */
export function ExchangeRatesPage() {
  const [currency, setCurrency] = useState('')
  const [page, setPage] = useState(0)
  const rates = useQuery({
    queryKey: ['exchange-rates', currency, page],
    queryFn: () => listExchangeRates({ baseCurrency: currency || undefined, page, size: 20 }),
    placeholderData: keepPreviousData,
  })
  return (
    <section aria-labelledby="rates-heading">
      <h1 id="rates-heading">환율</h1>
      <Field label="통화">
        <input pattern="[A-Z]{3}" maxLength={3} value={currency}
          onChange={(e) => { setCurrency(e.target.value.toUpperCase()); setPage(0) }} placeholder="전체" />
      </Field>
      <QueryView query={rates}>
        {(data) =>
          data.items.length === 0 ? (
            <Empty>등록된 환율이 없습니다.</Empty>
          ) : (
            <>
              <table>
                <thead>
                  <tr>
                    <th scope="col">ID</th>
                    <th scope="col">통화</th>
                    <th scope="col">환율(1 단위 = KRW)</th>
                    <th scope="col">발효</th>
                    <th scope="col">만료</th>
                    <th scope="col">메모</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((rate) => (
                    <tr key={rate.id}>
                      <td>#{rate.id}</td>
                      <td>{rate.baseCurrency}/{rate.quoteCurrency}</td>
                      <td>{rate.rate}</td>
                      <td>{formatInstant(rate.effectiveFrom)}</td>
                      <td>{formatInstant(rate.validUntil)}</td>
                      <td>{rate.note ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
            </>
          )
        }
      </QueryView>
      <CreateRateForm />
    </section>
  )
}

function CreateRateForm() {
  const queryClient = useQueryClient()
  const [baseCurrency, setBaseCurrency] = useState('USD')
  const [rate, setRate] = useState('')
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [note, setNote] = useState('')
  const create = useMutation({
    mutationFn: () =>
      createExchangeRate({
        baseCurrency,
        rate,
        // datetime-local은 브라우저 시간대 기준 → UTC ISO로 변환
        ...(effectiveFrom ? { effectiveFrom: new Date(effectiveFrom).toISOString() } : {}),
        ...(note ? { note } : {}),
      }),
    onSuccess: async () => {
      setRate('')
      setEffectiveFrom('')
      setNote('')
      await queryClient.invalidateQueries({ queryKey: ['exchange-rates'] })
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    create.mutate()
  }
  return (
    <form onSubmit={submit} className="card" aria-labelledby="create-rate-heading">
      <h2 id="create-rate-heading">환율 등록</h2>
      <Field label="통화(KRW 제외)">
        <input required pattern="[A-Z]{3}" maxLength={3} value={baseCurrency}
          onChange={(e) => setBaseCurrency(e.target.value.toUpperCase())} />
      </Field>
      <Field label="환율(KRW)" hint="소수 최대 10자리">
        <input required inputMode="decimal" pattern="(0|[1-9][0-9]{0,13})(\.[0-9]{1,10})?" value={rate}
          onChange={(e) => setRate(e.target.value)} />
      </Field>
      <Field label="발효 시각(선택, 브라우저 시간대)" hint="비우면 지금. 허용: 5분 전 ~ 7일 후">
        <input type="datetime-local" value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} />
      </Field>
      <Field label="메모">
        <input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <button type="submit" className="primary" disabled={create.isPending}>등록</button>
      <ErrorAlert error={create.error} />
    </form>
  )
}

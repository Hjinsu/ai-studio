import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import {
  adjustReservation,
  getReservation,
  newIdempotencyKey,
  resolveReservation,
  type ReservationDetail,
  type Schemas,
} from '../api/admin'
import { ConfirmDialog, ErrorAlert, Field, QueryView } from '../ui/components'
import { formatInstant, money } from '../ui/format'
import { LedgerTable } from './LedgerTable'

export function ReservationDetailPage() {
  const { reservationId = '' } = useParams()
  const reservation = useQuery({ queryKey: ['reservation', reservationId], queryFn: () => getReservation(reservationId) })
  return (
    <section aria-labelledby="reservation-heading">
      <QueryView query={reservation}>
        {(r) => (
          <>
            <h1 id="reservation-heading">
              예약 #{r.id} <span className="badge">{r.status}</span>
            </h1>
            <dl className="meta">
              <dt>요청 키</dt>
              <dd><code>{r.requestKey}</code></dd>
              <dt>용도</dt>
              <dd>{r.purpose}{r.channelId && <> · 채널 #{r.channelId}</>}</dd>
              <dt>예상(원본)</dt>
              <dd>{money(r.estimateOriginal, r.originalCurrency)}</dd>
              <dt>고정 환율</dt>
              <dd>{r.fxRate}{r.exchangeRateId && <> (환율 #{r.exchangeRateId})</>}</dd>
              <dt>예약 KRW</dt>
              <dd>{money(r.estimateKrw)}</dd>
              <dt>남은 예약</dt>
              <dd>{money(r.reservedRemainingKrw)}</dd>
              <dt>인식 비용</dt>
              <dd>{money(r.recognizedCostKrw)} / {money(r.recognizedCostOriginal, r.originalCurrency)}</dd>
              {r.reconciliationReason && (
                <>
                  <dt>확인 필요 사유</dt>
                  <dd>{r.reconciliationReason}</dd>
                </>
              )}
              <dt>연결 bucket</dt>
              <dd>{r.bucketIds.map((id) => `#${id}`).join(', ')}</dd>
              <dt>생성 / 종료</dt>
              <dd>{formatInstant(r.createdAt)} / {formatInstant(r.closedAt)}</dd>
            </dl>
            {(r.status === 'ACTIVE' || r.status === 'RECONCILIATION_REQUIRED' || r.status === 'RELEASED') && (
              <ResolveForm reservation={r} />
            )}
            {r.status === 'SETTLED' && <AdjustForm reservation={r} />}
            <h2>원장 이벤트</h2>
            <LedgerTable entries={r.events} />
          </>
        )}
      </QueryView>
    </section>
  )
}

function useRefreshReservation(id: string) {
  const queryClient = useQueryClient()
  return async () => {
    await queryClient.invalidateQueries({ queryKey: ['reservation', id] })
    await queryClient.invalidateQueries({ queryKey: ['reservations'] })
    await queryClient.invalidateQueries({ queryKey: ['ledger'] })
    await queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }
}

/**
 * 관리자 정산 확인(ADR-028, 048): 과금됨(확정 총액) 또는 미과금(해제). RELEASED는 과금됨(지연 과금)만 가능하며
 * 근거(외부 참조)·사유가 필요하다. 한 번의 작업 동안 같은 멱등 키를 유지해 재시도 시 중복 기록되지 않는다.
 */
function ResolveForm({ reservation }: { reservation: ReservationDetail }) {
  const refresh = useRefreshReservation(reservation.id)
  const late = reservation.status === 'RELEASED'
  const [outcome, setOutcome] = useState<'CHARGED' | 'NOT_CHARGED'>('CHARGED')
  const [amount, setAmount] = useState('')
  const [externalRef, setExternalRef] = useState('')
  const [note, setNote] = useState('')
  const [open, setOpen] = useState(false)
  const [key, setKey] = useState(newIdempotencyKey)
  // RELEASED 예약은 지연 과금(CHARGED)만 가능
  const effectiveOutcome = late ? 'CHARGED' : outcome
  const resolve = useMutation({
    mutationFn: () => {
      const body: Schemas['ReservationResolveRequest'] = { outcome: effectiveOutcome, note }
      if (effectiveOutcome === 'CHARGED') body.finalAmountOriginal = amount
      if (externalRef) body.externalRef = externalRef
      return resolveReservation(reservation.id, key, body)
    },
    onSuccess: async () => {
      setOpen(false)
      setKey(newIdempotencyKey())
      await refresh()
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    resolve.reset()
    setOpen(true)
  }
  return (
    <form onSubmit={submit} className="card" aria-labelledby="resolve-heading">
      <h2 id="resolve-heading">{late ? '지연 과금 확정' : '정산 확인'}</h2>
      {!late && (
        <Field label="결과">
          <select value={outcome} onChange={(e) => setOutcome(e.target.value as 'CHARGED' | 'NOT_CHARGED')}>
            <option value="CHARGED">과금됨(확정 총액 입력)</option>
            <option value="NOT_CHARGED">미과금(예약 해제)</option>
          </select>
        </Field>
      )}
      {effectiveOutcome === 'CHARGED' && (
        <Field label={`확정 총액(${reservation.originalCurrency})`} hint="예약 통화 기준 총액. 소수 최대 12자리">
          <input required inputMode="decimal" pattern="(0|[1-9][0-9]{0,11})(\.[0-9]{1,12})?" value={amount}
            onChange={(e) => setAmount(e.target.value)} />
        </Field>
      )}
      <Field label={late ? '외부 근거(필수)' : '외부 근거(선택)'}>
        <input required={late} maxLength={128} value={externalRef} onChange={(e) => setExternalRef(e.target.value)} />
      </Field>
      <Field label="판단 근거(필수, 감사 기록)">
        <input required maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <button type="submit" className="primary">확인</button>
      <ConfirmDialog
        open={open}
        title="정산 결과를 기록할까요?"
        confirmLabel="기록"
        danger
        busy={resolve.isPending}
        onConfirm={() => resolve.mutate()}
        onCancel={() => setOpen(false)}
      >
        <p>
          {effectiveOutcome === 'CHARGED'
            ? `확정 총액 ${money(amount, reservation.originalCurrency)}을 원장에 기록합니다.`
            : '남은 예약을 해제합니다.'}{' '}
          원장 기록은 수정·삭제할 수 없습니다.
        </p>
        <ErrorAlert error={resolve.error} />
      </ConfirmDialog>
    </form>
  )
}

/** SETTLED 예약의 조정·환불(ADR-057). basis=KRW는 원본 통화 인식 비용을 바꾸지 않는다(예: FX_DIFFERENCE). */
function AdjustForm({ reservation }: { reservation: ReservationDetail }) {
  const refresh = useRefreshReservation(reservation.id)
  const [entryType, setEntryType] = useState<'ADJUSTMENT' | 'REFUND'>('ADJUSTMENT')
  const [basis, setBasis] = useState<'ORIGINAL' | 'KRW'>('ORIGINAL')
  const [amount, setAmount] = useState('')
  const [reasonCode, setReasonCode] = useState('MANUAL_CORRECTION')
  const [externalRef, setExternalRef] = useState('')
  const [note, setNote] = useState('')
  const [open, setOpen] = useState(false)
  const [key, setKey] = useState(newIdempotencyKey)
  const adjust = useMutation({
    mutationFn: () =>
      adjustReservation(reservation.id, key, {
        entryType,
        basis,
        amount,
        reasonCode,
        note,
        ...(externalRef ? { externalRef } : {}),
      }),
    onSuccess: async () => {
      setOpen(false)
      setKey(newIdempotencyKey())
      await refresh()
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    adjust.reset()
    setOpen(true)
  }
  const currency = basis === 'KRW' ? 'KRW' : reservation.originalCurrency
  return (
    <form onSubmit={submit} className="card" aria-labelledby="adjust-heading">
      <h2 id="adjust-heading">조정·환불</h2>
      <Field label="유형">
        <select value={entryType} onChange={(e) => setEntryType(e.target.value as 'ADJUSTMENT' | 'REFUND')}>
          <option value="ADJUSTMENT">조정(부호 있는 증감)</option>
          <option value="REFUND">환불(양수)</option>
        </select>
      </Field>
      <Field label="기준 통화">
        <select value={basis} onChange={(e) => setBasis(e.target.value as 'ORIGINAL' | 'KRW')}>
          <option value="ORIGINAL">원본 통화({reservation.originalCurrency})</option>
          <option value="KRW">KRW 전용(원본 통화 비용 불변)</option>
        </select>
      </Field>
      <Field label={`금액(${currency})`}>
        <input required inputMode="decimal" pattern="-?(0|[1-9][0-9]{0,11})(\.[0-9]{1,12})?" value={amount}
          onChange={(e) => setAmount(e.target.value)} />
      </Field>
      <Field label="사유 코드" hint="예: FX_DIFFERENCE, PROVIDER_INVOICE, MANUAL_CORRECTION">
        <input required pattern="[A-Z_]{2,32}" value={reasonCode} onChange={(e) => setReasonCode(e.target.value)} />
      </Field>
      <Field label="외부 근거(선택)">
        <input maxLength={128} value={externalRef} onChange={(e) => setExternalRef(e.target.value)} />
      </Field>
      <Field label="메모(필수, 감사 기록)">
        <input required maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <button type="submit" className="primary">기록</button>
      <ConfirmDialog
        open={open}
        title={`${entryType === 'REFUND' ? '환불' : '조정'}을 기록할까요?`}
        confirmLabel="기록"
        danger
        busy={adjust.isPending}
        onConfirm={() => adjust.mutate()}
        onCancel={() => setOpen(false)}
      >
        <p>
          {money(amount, currency)} · {reasonCode}. 원장 기록은 수정·삭제할 수 없습니다.
        </p>
        <ErrorAlert error={adjust.error} />
      </ConfirmDialog>
    </form>
  )
}

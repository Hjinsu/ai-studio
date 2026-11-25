import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { adjustReservation, getReservation, newIdempotencyKey, resolveReservation, type ReservationDetail, type Schemas } from '../api/admin'
import { useChannelName } from '../api/useChannels'
import { formatMoney, matchesAmount } from '../lib/decimal'
import { fieldErrors } from '../lib/errors'
import { ENTRY_TYPE, REASON, RESERVATION_STATUS, label, text } from '../lib/labels'
import { Button } from '../ui/Button'
import { Card, StatCard } from '../ui/Card'
import { ConfirmDialog } from '../ui/Dialog'
import { DateTime, Decimal, Money } from '../ui/Display'
import { FormField, Input, Select } from '../ui/Form'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, ErrorState, InlineNotice, QueryView } from '../ui/States'
import { LabelBadge } from '../ui/StatusBadge'
import { useToast } from '../ui/toast-context'
import { Delta } from './LedgerTable'

export function ReservationDetailPage() {
  const { reservationId = '' } = useParams()
  const channelName = useChannelName()
  const reservation = useQuery({ queryKey: ['reservation', reservationId], queryFn: () => getReservation(reservationId) })
  return (
    <QueryView query={reservation} rows={6}>
      {(r) => (
        <>
          <PageHeader
            title={`예약 #${r.id}`}
            meta={<LabelBadge value={label(RESERVATION_STATUS, r.status)} />}
            description={<>{r.purpose} · {r.channelId ? channelName(r.channelId) : '채널 없음'} · 요청 키 <span className="num break-all">{r.requestKey}</span></>}
          />
          {r.status === 'RECONCILIATION_REQUIRED' && (
            <div className="mb-5">
              <InlineNotice tone="danger">
                <strong>정산 확인 필요</strong> — 외부 결과를 확인하지 못했습니다{r.reconciliationReason ? `(사유: ${r.reconciliationReason})` : ''}. 자동으로
                해제되지 않으니 provider 청구 내역을 확인해 아래에서 과금 여부를 기록하세요.
              </InlineNotice>
            </div>
          )}
          <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard label="예상 비용" value={<Money value={r.estimateKrw} />} detail={r.originalCurrency !== 'KRW' ? <><Money value={r.estimateOriginal} currency={r.originalCurrency} /> · 고정 환율 <Decimal value={r.fxRate} /></> : undefined} />
            <StatCard label="남은 예약" value={<Money value={r.reservedRemainingKrw} />} detail="아직 비용으로 확정되지 않은 예약" />
            <StatCard label="인식 비용" value={<Money value={r.recognizedCostKrw} />} detail={r.originalCurrency !== 'KRW' ? <Money value={r.recognizedCostOriginal} currency={r.originalCurrency} /> : undefined} />
            <StatCard label="생성 / 종료" value={<DateTime value={r.createdAt} showZone={false} />} detail={r.closedAt ? <>종료 <DateTime value={r.closedAt} showZone={false} /></> : '진행 중'} />
          </div>
          <div className="grid items-start gap-5 lg:grid-cols-[1fr_24rem]">
            <Card title="원장 타임라인" description="예약 → 잠정 비용 → 확정 비용(또는 해제) → 조정·환불 순서로 기록됩니다. 기록은 수정·삭제할 수 없습니다.">
              <Timeline reservation={r} />
            </Card>
            <div className="grid content-start gap-5">
              {(r.status === 'ACTIVE' || r.status === 'RECONCILIATION_REQUIRED' || r.status === 'RELEASED') && <ResolveForm reservation={r} />}
              {r.status === 'SETTLED' && <AdjustForm reservation={r} />}
              <Card title="연결된 기간 예산">
                <p className="text-sm text-muted">
                  이 예약의 모든 증감은 연결된 기간 예산 {r.bucketIds.length}개에 같은 금액으로 반영됩니다(기간이 끝난 뒤의 정산 포함).
                </p>
              </Card>
            </div>
          </div>
        </>
      )}
    </QueryView>
  )
}

function Timeline({ reservation }: { reservation: ReservationDetail }) {
  if (reservation.events.length === 0) {
    return <EmptyState title="원장 이벤트가 없습니다" />
  }
  const events = [...reservation.events].sort((a, b) => a.recordedAt.localeCompare(b.recordedAt) || Number(a.id) - Number(b.id))
  return (
    <ol aria-label="원장 이벤트" className="grid gap-0">
      {events.map((event, index) => {
        const type = label(ENTRY_TYPE, event.entryType)
        return (
          <li key={event.id} className="relative grid grid-cols-[1.25rem_1fr] gap-3 pb-5 last:pb-0">
            <div aria-hidden className="relative flex justify-center">
              <span className="mt-1.5 size-2.5 rounded-full border-2 border-primary bg-surface" />
              {index < events.length - 1 && <span className="absolute top-5 bottom-[-0.25rem] w-px bg-line" />}
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <LabelBadge value={type} />
                <span className="text-sm text-muted"><DateTime value={event.occurredAt} /></span>
                {event.reasonCode && <span className="text-sm">{text(REASON, event.reasonCode)}</span>}
              </div>
              <p className="mt-1 text-sm text-muted">{ENTRY_TYPE[event.entryType]?.help}</p>
              <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-3">
                <div><dt className="text-xs text-muted">금액</dt><dd><Money value={event.originalAmount} currency={event.originalCurrency} /></dd></div>
                <div><dt className="text-xs text-muted">예약 증감</dt><dd><Delta value={event.reserveDeltaKrw} kind="reserve" /></dd></div>
                <div><dt className="text-xs text-muted">비용 증감</dt><dd><Delta value={event.costDeltaKrw} kind="cost" /></dd></div>
              </dl>
              {event.externalRef && <p className="mt-1 text-xs text-muted">외부 근거 {event.externalRef}</p>}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

function useRefreshReservation(id: string) {
  const queryClient = useQueryClient()
  return async () => {
    for (const key of [['reservation', id], ['reservations'], ['ledger'], ['dashboard'], ['usage']]) {
      await queryClient.invalidateQueries({ queryKey: key })
    }
  }
}

/**
 * 관리자 정산 확인(ADR-028, 048): 과금됨(확정 총액) 또는 미과금(해제). RELEASED는 지연 과금(과금됨)만 가능하며
 * 외부 근거·사유가 필수다. 한 번의 작업 동안 같은 멱등 키를 유지해 재시도해도 중복 기록되지 않는다.
 */
function ResolveForm({ reservation }: { reservation: ReservationDetail }) {
  const refresh = useRefreshReservation(reservation.id)
  const toast = useToast()
  const late = reservation.status === 'RELEASED'
  const [outcome, setOutcome] = useState<'CHARGED' | 'NOT_CHARGED'>('CHARGED')
  const [amount, setAmount] = useState('')
  const [externalRef, setExternalRef] = useState('')
  const [note, setNote] = useState('')
  const [open, setOpen] = useState(false)
  const [key, setKey] = useState(newIdempotencyKey)
  const [localError, setLocalError] = useState<string | undefined>()
  const effectiveOutcome = late ? 'CHARGED' : outcome
  const resolve = useMutation({
    mutationFn: () => {
      const body: Schemas['ReservationResolveRequest'] = { outcome: effectiveOutcome, note: note.trim() }
      if (effectiveOutcome === 'CHARGED') body.finalAmountOriginal = amount
      if (externalRef.trim()) body.externalRef = externalRef.trim()
      return resolveReservation(reservation.id, key, body)
    },
    onSuccess: async () => {
      setOpen(false)
      setKey(newIdempotencyKey())
      toast.success(effectiveOutcome === 'CHARGED' ? '확정 비용을 기록했습니다.' : '예약을 해제했습니다.')
      await refresh()
    },
  })
  const errors = fieldErrors(resolve.error)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (effectiveOutcome === 'CHARGED' && !matchesAmount(amount, 12, 12)) {
      setLocalError('0 이상의 금액을 입력하세요(소수 최대 12자리).')
      return
    }
    setLocalError(undefined)
    resolve.reset()
    setOpen(true)
  }
  return (
    <Card title={late ? '지연 과금 확정' : '정산 확인'} tone={reservation.status === 'RECONCILIATION_REQUIRED' ? 'danger' : undefined}>
      <form onSubmit={submit} className="grid gap-4" aria-label={late ? '지연 과금 확정' : '정산 확인'}>
        {late ? (
          <p className="text-sm text-muted">해제된 예약에 나중에 청구된 비용을 기록합니다. 외부 근거와 판단 근거가 필요합니다.</p>
        ) : (
          <FormField label="결과">
            <Select value={outcome} onChange={(e) => setOutcome(e.target.value as 'CHARGED' | 'NOT_CHARGED')}>
              <option value="CHARGED">과금됨 — 확정 총액 기록</option>
              <option value="NOT_CHARGED">과금 안 됨 — 예약 해제</option>
            </Select>
          </FormField>
        )}
        {effectiveOutcome === 'CHARGED' && (
          <FormField label={`확정 총액(${reservation.originalCurrency})`} hint="예약 통화 기준 전체 금액" error={localError ?? errors.finalAmountOriginal}>
            <Input required inputMode="decimal" className="num" value={amount} onChange={(e) => setAmount(e.target.value.replace(/,/g, ''))} />
          </FormField>
        )}
        <FormField label={late ? '외부 근거(필수)' : '외부 근거'} optional={!late} hint="청구서 번호 등" error={errors.externalRef}>
          <Input required={late} maxLength={128} value={externalRef} onChange={(e) => setExternalRef(e.target.value)} />
        </FormField>
        <FormField label="판단 근거(필수, 감사 기록)" error={errors.note}>
          <Input required maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>
        <div>
          <Button type="submit" variant="primary">기록 전 확인</Button>
        </div>
      </form>
      <ConfirmDialog
        open={open}
        title="정산 결과를 원장에 기록할까요?"
        confirmLabel="원장에 기록"
        busy={resolve.isPending}
        onConfirm={() => resolve.mutate()}
        onCancel={() => setOpen(false)}
      >
        <p>
          {effectiveOutcome === 'CHARGED'
            ? `확정 총액 ${formatMoney(amount || '0', reservation.originalCurrency)}을 기록합니다.`
            : '남은 예약을 해제합니다.'}
        </p>
        <p className="text-sm text-muted">원장 기록은 수정·삭제할 수 없습니다. 잘못 기록하면 조정·환불로 바로잡습니다.</p>
        {resolve.error && <ErrorState error={resolve.error} compact />}
      </ConfirmDialog>
    </Card>
  )
}

/** SETTLED 예약의 조정·환불(ADR-057). KRW 기준은 원본 통화 비용을 바꾸지 않는다(예: 환율 차이). */
function AdjustForm({ reservation }: { reservation: ReservationDetail }) {
  const refresh = useRefreshReservation(reservation.id)
  const toast = useToast()
  const [entryType, setEntryType] = useState<'ADJUSTMENT' | 'REFUND'>('ADJUSTMENT')
  const [basis, setBasis] = useState<'ORIGINAL' | 'KRW'>('ORIGINAL')
  const [amount, setAmount] = useState('')
  const [reasonCode, setReasonCode] = useState('MANUAL_CORRECTION')
  const [externalRef, setExternalRef] = useState('')
  const [note, setNote] = useState('')
  const [open, setOpen] = useState(false)
  const [key, setKey] = useState(newIdempotencyKey)
  const [localError, setLocalError] = useState<string | undefined>()
  const adjust = useMutation({
    mutationFn: () => adjustReservation(reservation.id, key, { entryType, basis, amount, reasonCode, note: note.trim(), ...(externalRef.trim() ? { externalRef: externalRef.trim() } : {}) }),
    onSuccess: async () => {
      setOpen(false)
      setKey(newIdempotencyKey())
      toast.success(entryType === 'REFUND' ? '환불을 기록했습니다.' : '조정을 기록했습니다.')
      await refresh()
    },
  })
  const errors = fieldErrors(adjust.error)
  const currency = basis === 'KRW' ? 'KRW' : reservation.originalCurrency
  const submit = (event: FormEvent) => {
    event.preventDefault()
    // 서버 검증과 같은 자릿수: 정수 최대 12자리, 소수는 원화 기준 6자리·원본 통화 12자리
    const fractionDigits = basis === 'KRW' ? 6 : 12
    if (!matchesAmount(amount, 12, fractionDigits, entryType === 'ADJUSTMENT')) {
      setLocalError(
        `${entryType === 'REFUND' ? '환불액은 양수로' : '증감액을(감소는 -)'} 입력하세요. 소수는 최대 ${fractionDigits}자리입니다.`,
      )
      return
    }
    setLocalError(undefined)
    adjust.reset()
    setOpen(true)
  }
  return (
    <Card title="조정·환불" description="정산 완료 후 비용을 바로잡습니다.">
      <form onSubmit={submit} className="grid gap-4" aria-label="조정·환불">
        <div className="grid grid-cols-2 gap-3">
          <FormField label="유형">
            <Select value={entryType} onChange={(e) => setEntryType(e.target.value as 'ADJUSTMENT' | 'REFUND')}>
              <option value="ADJUSTMENT">조정(증감)</option>
              <option value="REFUND">환불</option>
            </Select>
          </FormField>
          <FormField label="기준 통화">
            <Select value={basis} onChange={(e) => setBasis(e.target.value as 'ORIGINAL' | 'KRW')}>
              <option value="ORIGINAL">원본({reservation.originalCurrency})</option>
              <option value="KRW">원화 전용</option>
            </Select>
          </FormField>
        </div>
        {basis === 'KRW' && reservation.originalCurrency !== 'KRW' && (
          <InlineNotice>원화 전용 조정은 {reservation.originalCurrency} 기준 비용을 바꾸지 않습니다. 실제 결제 환율 차이 등에 씁니다.</InlineNotice>
        )}
        <FormField label={`금액(${currency})`} hint={`${entryType === 'ADJUSTMENT' ? '감소는 앞에 - 를 붙입니다.' : '양수'} 소수 최대 ${basis === 'KRW' ? 6 : 12}자리`} error={localError ?? errors.amount}>
          <Input required inputMode="decimal" className="num" value={amount} onChange={(e) => setAmount(e.target.value.replace(/,/g, ''))} />
        </FormField>
        <FormField label="사유 코드" hint="FX_DIFFERENCE(환율 차이), PROVIDER_INVOICE(청구서), MANUAL_CORRECTION(수동 정정)" error={errors.reasonCode}>
          <Input required pattern="[A-Z_]{2,32}" value={reasonCode} onChange={(e) => setReasonCode(e.target.value.toUpperCase())} />
        </FormField>
        <FormField label="외부 근거" optional error={errors.externalRef}>
          <Input maxLength={128} value={externalRef} onChange={(e) => setExternalRef(e.target.value)} />
        </FormField>
        <FormField label="메모(필수, 감사 기록)" error={errors.note}>
          <Input required maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>
        <div>
          <Button type="submit" variant="primary">기록 전 확인</Button>
        </div>
      </form>
      <ConfirmDialog
        open={open}
        title={`${entryType === 'REFUND' ? '환불' : '조정'}을 원장에 기록할까요?`}
        confirmLabel="원장에 기록"
        busy={adjust.isPending}
        onConfirm={() => adjust.mutate()}
        onCancel={() => setOpen(false)}
      >
        <p>
          {formatMoney(amount || '0', currency)} · {text(REASON, reasonCode)}
        </p>
        <p className="text-sm text-muted">원장 기록은 수정·삭제할 수 없습니다.</p>
        {adjust.error && <ErrorState error={adjust.error} compact />}
      </ConfirmDialog>
    </Card>
  )
}

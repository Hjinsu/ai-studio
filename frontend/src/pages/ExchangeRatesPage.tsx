import { useState, type FormEvent } from 'react'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { assertSession } from '../api/http'
import { invalidateSessionQueries, useSessionMutation } from '../api/useSessionMutation'
import { ArrowLeftRight, Plus } from 'lucide-react'
import { createExchangeRate, listExchangeRates, type ExchangeRate } from '../api/admin'
import { formatKrw, isDecimal, matchesAmount } from '../lib/decimal'
import { fieldErrors } from '../lib/errors'
import { browserTimeZone, remaining } from '../lib/time'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { DataTable, Pagination } from '../ui/DataTable'
import { Dialog } from '../ui/Dialog'
import { DateTime, Rate } from '../ui/Display'
import { DateTimePicker, FormField, Input, Select } from '../ui/Form'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, ErrorState, InlineNotice, QueryView } from '../ui/States'
import { StatusBadge } from '../ui/StatusBadge'
import { useToast } from '../ui/toast-context'

/** 자주 쓰는 통화. 목록에 없으면 직접 입력(ISO 4217, KRW 제외). */
const CURRENCIES: { code: string; name: string }[] = [
  { code: 'USD', name: '미국 달러' },
  { code: 'EUR', name: '유로' },
  { code: 'JPY', name: '일본 엔' },
  { code: 'GBP', name: '영국 파운드' },
  { code: 'CNY', name: '중국 위안' },
]

type RateStatus = 'current' | 'scheduled' | 'expired' | 'superseded'

/**
 * 환율 상태(표시용, 브라우저 시각 기준). 통화별로 발효된 가장 최근 환율 1건만 "적용 중"이며, 그 환율이 만료되면 이전
 * 환율로 대체하지 않는다(ADR-046) — 서버가 같은 규칙으로 판단한다.
 * "가장 최근"은 서버 목록 순서(발효 시각 내림차순, id 내림차순)를 그대로 쓴다. 시각 문자열을 화면에서 다시 정렬하지
 * 않는다(소수초 표기가 다르면 문자열 순서가 실제 시각 순서와 달라진다, UI-RV-04). 시각 비교는 Date 값으로 한다.
 */
function statusOf(rate: ExchangeRate, all: ExchangeRate[], now: number): RateStatus {
  if (Date.parse(rate.effectiveFrom) > now) return 'scheduled'
  const latest = all.find((r) => r.baseCurrency === rate.baseCurrency && Date.parse(r.effectiveFrom) <= now)
  if (latest?.id !== rate.id) return 'superseded'
  return Date.parse(rate.validUntil) <= now ? 'expired' : 'current'
}

const STATUS: Record<RateStatus, { text: string; tone: 'success' | 'primary' | 'neutral' | 'danger' }> = {
  current: { text: '적용 중', tone: 'success' },
  scheduled: { text: '적용 예정', tone: 'primary' },
  expired: { text: '만료', tone: 'danger' },
  superseded: { text: '이전 환율', tone: 'neutral' },
}

export function ExchangeRatesPage() {
  const [currency, setCurrency] = useState('')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const [now] = useState(() => Date.now())
  const rates = useQuery({
    queryKey: ['exchange-rates', currency, page],
    queryFn: () => listExchangeRates({ baseCurrency: currency || undefined, page, size: 50 }),
    placeholderData: keepPreviousData,
  })
  return (
    <>
      <PageHeader
        title="환율 관리"
        description="외화 비용을 원화로 예약할 때 쓰는 환율입니다. 관리자가 직접 등록하며 자동 수집하지 않습니다."
        actions={<Button variant="primary" icon={<Plus aria-hidden className="size-4" />} onClick={() => setCreating(true)}>환율 등록</Button>}
      />
      <InlineNotice tone="primary">
        환율은 등록 후 바꿀 수 없고, 발효 후 72시간 동안 유효합니다. 가장 최근 환율이 만료되면 이전 환율을 쓰지 않고 해당 통화의 신규 예약이 차단됩니다.
        이미 잡힌 예약은 예약 시점의 환율로 정산합니다.
      </InlineNotice>
      <div className="mt-4 mb-3">
        <FormField label="통화" className="w-56">
          <Select value={currency} onChange={(e) => { setCurrency(e.target.value); setPage(0) }}>
            <option value="">전체 통화</option>
            {CURRENCIES.map((c) => <option key={c.code} value={c.code}>{c.code} · {c.name}</option>)}
          </Select>
        </FormField>
      </div>
      <QueryView query={rates}>
        {(data) => {
          if (data.items.length === 0) {
            return <EmptyState icon={<ArrowLeftRight className="size-6" />} title="등록된 환율이 없습니다" description="외화 비용을 예약하려면 해당 통화의 환율이 필요합니다." action={<Button variant="primary" onClick={() => setCreating(true)}>환율 등록</Button>} />
          }
          const current = data.items.filter((r) => statusOf(r, data.items, now) === 'current')
          return (
            <div className="grid gap-5">
              {page === 0 && current.length > 0 && (
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {current.map((rate) => (
                    <Card key={rate.id}>
                      <div className="flex items-start justify-between gap-2">
                        <p className="num text-lg font-semibold"><Rate base={rate.baseCurrency} rate={rate.rate} /></p>
                        <StatusBadge tone="success">적용 중</StatusBadge>
                      </div>
                      <p className="mt-2 text-sm text-muted">
                        만료 <DateTime value={rate.validUntil} showZone={false} /> · {remaining(rate.validUntil, now) ?? '만료됨'} 남음
                      </p>
                    </Card>
                  ))}
                </div>
              )}
              <DataTable<ExchangeRate>
                caption="환율 목록"
                rows={data.items}
                rowKey={(r) => r.id}
                rowTone={(r) => (statusOf(r, data.items, now) === 'expired' ? 'danger' : undefined)}
                columns={[
                  { key: 'rate', header: '환율', cell: (r) => <Rate base={r.baseCurrency} rate={r.rate} /> },
                  { key: 'status', header: '상태', cell: (r) => { const s = STATUS[statusOf(r, data.items, now)]; return <StatusBadge tone={s.tone}>{s.text}</StatusBadge> } },
                  { key: 'from', header: '발효', cell: (r) => <DateTime value={r.effectiveFrom} showZone={false} /> },
                  { key: 'until', header: '만료', cell: (r) => <><DateTime value={r.validUntil} showZone={false} />{statusOf(r, data.items, now) === 'current' && <span className="block text-xs text-muted">{remaining(r.validUntil, now)} 남음</span>}</> },
                  { key: 'note', header: '메모', hideBelow: 'md', cell: (r) => r.note ?? <span className="text-muted">—</span> },
                ]}
              />
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
              <p className="text-xs text-muted">시각은 {browserTimeZone()} 기준입니다. 상태는 이 화면을 연 시각 기준이며 예약 시 서버가 다시 판단합니다.</p>
            </div>
          )
        }}
      </QueryView>
      {creating && <CreateRateDialog onClose={() => setCreating(false)} />}
    </>
  )
}

function CreateRateDialog({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [preset, setPreset] = useState('USD')
  const [custom, setCustom] = useState('')
  const [rate, setRate] = useState('')
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [note, setNote] = useState('')
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({})
  const baseCurrency = preset === 'OTHER' ? custom.trim().toUpperCase() : preset
  const create = useSessionMutation({
    mutationFn: () =>
      createExchangeRate({
        baseCurrency,
        rate,
        // datetime-local은 브라우저 시간대 기준 → UTC ISO
        ...(effectiveFrom ? { effectiveFrom: new Date(effectiveFrom).toISOString() } : {}),
        ...(note.trim() ? { note: note.trim() } : {}),
      }),
    onSuccess: async (created, _variables, generation) => {
      assertSession(generation)
      toast.success(`환율 1 ${created.baseCurrency} = ${formatKrw(created.rate)}을 등록했습니다.`, generation)
      await invalidateSessionQueries(queryClient, generation, ['exchange-rates'])
      assertSession(generation)
      onClose()
    },
  })
  const errors = { ...fieldErrors(create.error), ...localErrors }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!/^[A-Z]{3}$/.test(baseCurrency) || baseCurrency === 'KRW') next.baseCurrency = 'KRW가 아닌 3자리 통화 코드를 입력하세요.'
    if (!matchesAmount(rate, 14, 10) || /^0(\.0+)?$/.test(rate)) next.rate = '0보다 큰 환율을 입력하세요(소수 최대 10자리).'
    setLocalErrors(next)
    if (Object.keys(next).length === 0) create.mutate()
  }
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && !create.isPending && onClose()}
      title="환율 등록"
      description="수동 등록 환율입니다. 등록 후에는 바꿀 수 없으니 값을 확인하세요."
      footer={
        <>
          <Button onClick={onClose} disabled={create.isPending}>취소</Button>
          <Button type="submit" form="create-rate-form" variant="primary" loading={create.isPending}>환율 등록</Button>
        </>
      }
    >
      <form id="create-rate-form" onSubmit={submit} className="grid gap-4" aria-label="환율 등록">
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label="통화" error={errors.baseCurrency}>
            <Select value={preset} onChange={(e) => setPreset(e.target.value)}>
              {CURRENCIES.map((c) => <option key={c.code} value={c.code}>{c.code} · {c.name}</option>)}
              <option value="OTHER">기타 통화 직접 입력</option>
            </Select>
          </FormField>
          {preset === 'OTHER' && (
            <FormField label="통화 코드" hint="ISO 4217, 예: CAD">
              <Input required maxLength={3} value={custom} onChange={(e) => setCustom(e.target.value.toUpperCase())} />
            </FormField>
          )}
        </div>
        <FormField label={`1 ${baseCurrency || '통화'} 당 원화`} hint="소수 최대 10자리" error={errors.rate}>
          <Input required inputMode="decimal" className="num" value={rate} onChange={(e) => setRate(e.target.value.replace(/[,\s₩]/g, ''))} />
        </FormField>
        {rate && isDecimal(rate) && <p className="num -mt-2 text-sm text-muted">미리보기: 1 {baseCurrency} = {formatKrw(rate)}</p>}
        <FormField
          label="발효 시각"
          optional
          hint={`비우면 지금부터. 허용 범위: 5분 전 ~ 7일 후 (${browserTimeZone()} 기준). 발효 후 72시간 유효.`}
          error={errors.effectiveFrom}
        >
          <DateTimePicker value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} />
        </FormField>
        <FormField label="메모" optional hint="출처 등. 예: 은행 고시 매매기준율" error={errors.note}>
          <Input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>
        {create.error && <ErrorState error={create.error} compact />}
      </form>
    </Dialog>
  )
}

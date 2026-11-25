import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ShieldAlert, ShieldCheck, ShieldOff, X } from 'lucide-react'
import { budgetControl, listPolicies, runConsistencyCheck, setSpending, type Schemas } from '../api/admin'
import { CHECK_CODE, CHECK_DETAIL, CONSISTENCY, label } from '../lib/labels'
import { cn } from '../lib/cn'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { DataTable } from '../ui/DataTable'
import { ConfirmDialog } from '../ui/Dialog'
import { DateTime } from '../ui/Display'
import { FormField, Textarea } from '../ui/Form'
import { PageHeader } from '../ui/PageHeader'
import { ErrorState, InlineNotice, QueryView } from '../ui/States'
import { LabelBadge } from '../ui/StatusBadge'
import { useToast } from '../ui/toast-context'

type Issue = Schemas['ConsistencyReport']['issues'][number]

/**
 * 긴급 지출 차단(ADR-061)과 정합성 검사(ADR-026, 054). 지출 허용은 비용을 발생시킬 수 있는 위험 작업이다.
 * 차단 중에도 기존 예약의 정산·조회는 허용된다.
 */
export function SpendingControlPage() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const control = useQuery({ queryKey: ['budget-control'], queryFn: budgetControl })
  const systemPolicies = useQuery({ queryKey: ['policies', 'SYSTEM', 'ACTIVE', 0], queryFn: () => listPolicies({ scope: 'SYSTEM', status: 'ACTIVE', size: 100 }) })
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [report, setReport] = useState<Schemas['ConsistencyReport'] | null>(null)
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['budget-control'] })
    await queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }
  const change = useMutation({
    mutationFn: (body: Schemas['SpendingChangeRequest']) => setSpending(body),
    onSuccess: async (result) => {
      setOpen(false)
      setReason('')
      toast.success(result.spendingEnabled ? '신규 지출을 허용했습니다.' : '모든 신규 지출을 차단했습니다.')
      await refresh()
    },
  })
  const check = useMutation({
    mutationFn: runConsistencyCheck,
    onSuccess: async (result) => {
      setReport(result)
      toast[result.consistent ? 'success' : 'error'](result.consistent ? '정합성 검사: 정상' : `정합성 검사: 오류 ${result.issues.length}건`)
      await refresh()
    },
  })
  return (
    <>
      <PageHeader title="지출 제어" description="신규 비용 예약을 허용하거나 즉시 차단하고, 비용 기록의 정합성을 검사합니다." />
      <QueryView query={control} rows={4}>
        {(data) => {
          const enabled = data.spendingEnabled
          const consistency = label(CONSISTENCY, data.consistency.status)
          const periods = new Set((systemPolicies.data?.items ?? []).map((p) => p.periodType))
          const checks = [
            { ok: enabled, text: '지출이 허용되어 있음', detail: enabled ? undefined : '현재 관리자 차단 상태입니다.' },
            { ok: data.consistency.status !== 'ERROR', text: '정합성 상태가 오류가 아님', detail: `현재 ${consistency.text}` },
            { ok: periods.has('DAY'), text: '시스템 전체 일간 정책 활성', detail: systemPolicies.isPending ? '확인 중' : undefined },
            { ok: periods.has('MONTH'), text: '시스템 전체 월간 정책 활성', detail: systemPolicies.isPending ? '확인 중' : undefined },
          ]
          return (
            <div className="grid gap-5">
              <section
                aria-labelledby="spending-state"
                className={cn('rounded-lg border p-5', enabled ? 'border-line bg-surface' : 'border-warning bg-warning-soft')}
              >
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div className="flex items-start gap-3">
                    {enabled ? (
                      <ShieldCheck aria-hidden className="mt-0.5 size-6 text-success" />
                    ) : (
                      <ShieldOff aria-hidden className="mt-0.5 size-6 text-warning" />
                    )}
                    <div>
                      <h2 id="spending-state" className="text-lg font-semibold">
                        {enabled ? '신규 지출 허용 중' : '신규 지출 차단됨'}
                      </h2>
                      <p className="mt-1 max-w-2xl text-sm text-muted">
                        {enabled
                          ? '예산 조건을 만족하는 비용 예약이 가능합니다. 문제가 생기면 즉시 차단하세요.'
                          : '모든 신규 비용 예약이 거부됩니다(기본값). 이미 잡힌 예약의 정산과 조회는 계속됩니다.'}
                      </p>
                      {data.spendingChangedAt && (
                        <p className="mt-2 text-sm">
                          마지막 변경 <DateTime value={data.spendingChangedAt} /> · 관리자 #{data.spendingChangedBy ?? '—'}
                          {data.spendingChangeReason && <> · 사유 “{data.spendingChangeReason}”</>}
                        </p>
                      )}
                    </div>
                  </div>
                  {enabled ? (
                    <Button variant="danger" icon={<ShieldOff aria-hidden className="size-4" />} onClick={() => { change.reset(); setOpen(true) }}>
                      긴급 지출 차단
                    </Button>
                  ) : (
                    <Button variant="risk" icon={<ShieldAlert aria-hidden className="size-4" />} onClick={() => { change.reset(); setOpen(true) }}>
                      지출 허용…
                    </Button>
                  )}
                </div>
              </section>

              <Card title="신규 지출 조건" description="조회 가능한 조건만 표시합니다. 채널 상태·환율·예산 한도는 예약할 때 서버가 다시 검사합니다.">
                <ul className="grid gap-2">
                  {checks.map((item) => (
                    <li key={item.text} className="flex items-start gap-2.5">
                      {item.ok ? (
                        <Check aria-hidden className="mt-0.5 size-4 text-success" />
                      ) : (
                        <X aria-hidden className="mt-0.5 size-4 text-danger" />
                      )}
                      <span>
                        <span className="sr-only">{item.ok ? '충족: ' : '미충족: '}</span>
                        {item.text}
                        {item.detail && <span className="ml-1.5 text-sm text-muted">({item.detail})</span>}
                      </span>
                    </li>
                  ))}
                </ul>
              </Card>

              <Card
                title="정합성 검사"
                description="예약·기간 예산의 합계가 원장 기록과 일치하는지 확인합니다. 오류가 있으면 신규 예약이 차단되며 자동으로 고치지 않습니다."
                actions={<Button onClick={() => check.mutate()} loading={check.isPending}>{check.isPending ? '검사 중…' : '지금 검사'}</Button>}
              >
                <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-3">
                  <div>
                    <dt className="text-sm text-muted">상태</dt>
                    <dd className="mt-1"><LabelBadge value={consistency} /></dd>
                  </div>
                  <div>
                    <dt className="text-sm text-muted">마지막 검사</dt>
                    <dd className="mt-1">{data.consistency.checkedAt ? <DateTime value={data.consistency.checkedAt} /> : '검사한 적 없음'}</dd>
                  </div>
                  <div>
                    <dt className="text-sm text-muted">오류 건수</dt>
                    <dd className="num mt-1">{data.consistency.issueCount}건</dd>
                  </div>
                </dl>
                {check.error && <div className="mt-4"><ErrorState error={check.error} compact /></div>}
                {report && (
                  <div className="mt-5 grid gap-3" role="status">
                    {report.consistent ? (
                      <InlineNotice tone="primary">
                        <DateTime value={report.checkedAt} /> 검사 결과: 오류 없음
                      </InlineNotice>
                    ) : (
                      <>
                        <InlineNotice tone="danger">
                          <DateTime value={report.checkedAt} /> 검사에서 오류 {report.issues.length}건을 찾았습니다. 원인을 확인하기 전까지 신규 예약이 차단됩니다.
                        </InlineNotice>
                        <DataTable<Issue>
                          caption="정합성 오류"
                          rows={report.issues}
                          rowKey={(i) => `${i.checkCode}-${i.entityType}-${i.entityId}`}
                          columns={[
                            { key: 'what', header: '오류', cell: (i) => (
                              <>
                                <span className="block font-medium">{CHECK_CODE[i.checkCode] ?? i.checkCode}</span>
                                {CHECK_DETAIL[i.checkCode] && <span className="block text-sm">{CHECK_DETAIL[i.checkCode]}</span>}
                                <span className="text-xs text-muted">{i.checkCode}</span>
                              </>
                            ) },
                            { key: 'target', header: '대상', cell: (i) => `${i.entityType === 'RESERVATION' ? '예약' : i.entityType === 'BUCKET' ? '기간 예산' : '정책'} #${i.entityId}` },
                            { key: 'expected', header: '기대값', align: 'right', cell: (i) => i.expected },
                            { key: 'actual', header: '실제값', align: 'right', cell: (i) => i.actual },
                          ]}
                        />
                      </>
                    )}
                  </div>
                )}
              </Card>

              <ConfirmDialog
                open={open}
                title={enabled ? '모든 신규 지출을 차단할까요?' : '신규 지출을 허용할까요?'}
                confirmLabel={enabled ? '지출 차단' : '지출 허용'}
                tone={enabled ? 'danger' : 'risk'}
                busy={change.isPending}
                confirmDisabled={reason.trim() === ''}
                onConfirm={() => change.mutate({ enabled: !enabled, reason: reason.trim(), version: data.version })}
                onCancel={() => setOpen(false)}
              >
                {enabled ? (
                  <p>시스템 전체의 신규 비용 예약이 즉시 거부됩니다. 이미 잡힌 예약의 정산은 계속할 수 있습니다.</p>
                ) : (
                  <>
                    <p>
                      <strong>시스템 전체</strong>에서 예산 조건을 만족하는 비용 예약이 가능해집니다. 실제 provider가 연동되면 유료 호출로 이어질 수
                      있습니다.
                    </p>
                    <p className="text-sm text-muted">예산 정책·정합성·채널 상태·환율 검사는 그대로 적용됩니다.</p>
                  </>
                )}
                <FormField label="변경 사유(필수, 감사 기록)">
                  <Textarea required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} className="min-h-16" />
                </FormField>
                {change.error && <ErrorState error={change.error} compact />}
              </ConfirmDialog>
            </div>
          )
        }}
      </QueryView>
    </>
  )
}

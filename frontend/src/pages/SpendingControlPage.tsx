import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { budgetControl, runConsistencyCheck, setSpending, type Schemas } from '../api/admin'
import { ConfirmDialog, ErrorAlert, Field, QueryView } from '../ui/components'
import { formatInstant } from '../ui/format'

/**
 * 긴급 지출 차단(ADR-061)과 정합성 검사(ADR-026, 054). 차단 중에도 기존 예약의 정산·조회는 허용된다.
 * 정합성 ERROR면 신규 예약이 차단되고 다음 OK 검사 후 해제된다(자동 수정 없음).
 */
export function SpendingControlPage() {
  const queryClient = useQueryClient()
  const control = useQuery({ queryKey: ['budget-control'], queryFn: budgetControl })
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [report, setReport] = useState<Schemas['ConsistencyReport'] | null>(null)
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['budget-control'] })
    await queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }
  const change = useMutation({
    mutationFn: (body: Schemas['SpendingChangeRequest']) => setSpending(body),
    onSuccess: async () => {
      setOpen(false)
      setReason('')
      await refresh()
    },
  })
  const check = useMutation({
    mutationFn: runConsistencyCheck,
    onSuccess: async (result) => {
      setReport(result)
      await refresh()
    },
  })
  return (
    <section aria-labelledby="control-heading">
      <h1 id="control-heading">지출 제어·정합성</h1>
      <QueryView query={control}>
        {(data) => (
          <>
            <div className="card">
              <h2>지출 상태</h2>
              <p>
                현재 <strong className={data.spendingEnabled ? 'ok' : 'warn'}>{data.spendingEnabled ? '허용' : '차단'}</strong>
                {data.spendingChangedAt && (
                  <>
                    {' '}· {formatInstant(data.spendingChangedAt)} · 관리자 #{data.spendingChangedBy ?? '—'} · 사유{' '}
                    {data.spendingChangeReason ?? '—'}
                  </>
                )}
              </p>
              <button type="button" className={data.spendingEnabled ? 'danger' : 'primary'}
                onClick={() => { change.reset(); setOpen(true) }}>
                {data.spendingEnabled ? '긴급 지출 차단' : '지출 허용'}
              </button>
              <ConfirmDialog
                open={open}
                title={data.spendingEnabled ? '모든 신규 지출을 차단할까요?' : '신규 지출을 허용할까요?'}
                confirmLabel={data.spendingEnabled ? '차단' : '허용'}
                danger={data.spendingEnabled}
                busy={change.isPending}
                confirmDisabled={reason.trim() === ''}
                onConfirm={() => change.mutate({ enabled: !data.spendingEnabled, reason, version: data.version })}
                onCancel={() => setOpen(false)}
              >
                <p>
                  {data.spendingEnabled
                    ? '차단 중에도 기존 예약의 정산·조회는 계속됩니다.'
                    : '활성 SYSTEM DAY·MONTH 정책과 정합성 OK 상태가 있어야 실제 예약이 가능합니다.'}
                </p>
                <Field label="사유(필수, 감사 기록)">
                  <input required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
                </Field>
                <ErrorAlert error={change.error} />
              </ConfirmDialog>
            </div>
            <div className="card">
              <h2>정합성 검사</h2>
              <p>
                상태 <strong className={data.consistency.status === 'ERROR' ? 'warn' : 'ok'}>{data.consistency.status}</strong>{' '}
                · 이슈 {data.consistency.issueCount}건 · 마지막 검사 {formatInstant(data.consistency.checkedAt)}
              </p>
              <button type="button" onClick={() => check.mutate()} disabled={check.isPending}>
                {check.isPending ? '검사 중…' : '지금 검사'}
              </button>
              <ErrorAlert error={check.error} />
              {report && (
                <div role="status">
                  <p>
                    {formatInstant(report.checkedAt)} 검사 결과: {report.consistent ? '정상' : `이슈 ${report.issues.length}건`}
                  </p>
                  {report.issues.length > 0 && (
                    <table>
                      <thead>
                        <tr>
                          <th scope="col">검사</th>
                          <th scope="col">대상</th>
                          <th scope="col">기대값</th>
                          <th scope="col">실제값</th>
                        </tr>
                      </thead>
                      <tbody>
                        {report.issues.map((issue) => (
                          <tr key={`${issue.checkCode}-${issue.entityType}-${issue.entityId}`}>
                            <td>{issue.checkCode}</td>
                            <td>
                              {issue.entityType} #{issue.entityId}
                            </td>
                            <td>{issue.expected}</td>
                            <td>{issue.actual}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              )}
            </div>
          </>
        )}
      </QueryView>
    </section>
  )
}

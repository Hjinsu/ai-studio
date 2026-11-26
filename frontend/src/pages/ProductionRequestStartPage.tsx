import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Clapperboard } from 'lucide-react'
import { assertSession } from '../api/http'
import { budgetControl, newIdempotencyKey } from '../api/admin'
import { useAllChannels } from '../api/useChannels'
import { useSessionMutation } from '../api/useSessionMutation'
import {
  createProductionRequest,
  DEFAULT_CANDIDATES,
  MAX_CANDIDATES,
  MIN_CANDIDATES,
  type ContentType,
  type CreateProductionRequest,
  type SelectionMode,
} from '../api/production'
import { spendingView } from '../lib/budget'
import { fieldErrors } from '../lib/errors'
import { CONTENT_TYPE, text } from '../lib/labels'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { ChannelSelect } from '../ui/ChannelSelect'
import { ConfirmDialog } from '../ui/Dialog'
import { FormField, FormSection, Select } from '../ui/Form'
import { PageHeader } from '../ui/PageHeader'
import { ErrorState, InlineNotice } from '../ui/States'
import { useToast } from '../ui/toast-context'
import { SELECTION_MODE } from './ProductionRequestLabels'

const COUNTS = Array.from({ length: MAX_CANDIDATES - MIN_CANDIDATES + 1 }, (_, i) => MIN_CANDIDATES + i)

/**
 * 제작 시작(V2-02). 채널과 소재 선정 방식만 고르면 서버가 제작 요청과 비용 한도를 먼저 고정한 뒤 소재를 발굴한다.
 * 비용이 발생할 수 있는 작업이므로 확인 대화상자를 거치며, 한 번의 시작 작업 동안 같은 멱등 키를 유지한다.
 */
export function ProductionRequestStartPage() {
  const navigate = useNavigate()
  const toast = useToast()
  const channels = useAllChannels()
  const control = useQuery({ queryKey: ['budget-control'], queryFn: budgetControl })
  const [channelId, setChannelId] = useState('')
  const [contentType, setContentType] = useState<ContentType>('SHORTS')
  const [mode, setMode] = useState<SelectionMode>('AUTO')
  const [count, setCount] = useState(DEFAULT_CANDIDATES)
  const [confirming, setConfirming] = useState(false)
  const [key, setKey] = useState(newIdempotencyKey)
  const [missingChannel, setMissingChannel] = useState(false)

  const start = useSessionMutation({
    mutationFn: (body: CreateProductionRequest) => createProductionRequest(key, body),
    onSuccess: (summary, _variables, generation) => {
      assertSession(generation)
      setConfirming(false)
      setKey(newIdempotencyKey())
      toast.success('제작을 시작했습니다. 소재 발굴 결과를 이 화면에서 확인하세요.', generation)
      void navigate(`/production/${summary.id}`)
    },
  })

  const shorts = contentType === 'SHORTS'
  const body: CreateProductionRequest = shorts
    ? { channelId, contentType, selectionMode: mode, candidateCount: count }
    : { channelId, contentType }
  const channelName = channels.data?.find((channel) => channel.id === channelId)?.name ?? ''
  const spending = control.data ? spendingView(control.data.spendingEnabled, control.data.consistency.status) : null
  const errors = fieldErrors(start.error)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!channelId) {
      setMissingChannel(true)
      return
    }
    start.reset()
    setConfirming(true)
  }

  return (
    <>
      <PageHeader
        title="제작 시작"
        description="채널을 고르면 채널 설정을 바탕으로 소재 후보를 발굴하고 평가합니다. 소재를 직접 입력하지 않아도 됩니다."
      />
      <div className="grid max-w-3xl gap-5">
        {spending?.blocked && (
          <InlineNotice tone="warning">
            현재 신규 지출이 차단되어 있습니다. 제작 요청은 만들 수 있지만 비용 보호 규칙에 따라 소재 발굴이 실행되지 않고 실패로 끝납니다.
          </InlineNotice>
        )}
        <Card>
          <form className="grid gap-6" onSubmit={submit} noValidate>
            <FormSection title="대상" description="제작 요청은 소재 발굴 전에 채널 설정과 비용 한도를 고정합니다.">
              <FormField
                label="채널"
                error={missingChannel && !channelId ? '채널을 선택하세요.' : errors.channelId}
              >
                <ChannelSelect
                  excludeArchived
                  value={channelId}
                  onChange={(value) => {
                    setChannelId(value)
                    setMissingChannel(false)
                  }}
                />
              </FormField>
              <FormField label="콘텐츠 유형" error={errors.contentType}>
                <Select value={contentType} onChange={(e) => setContentType(e.target.value as ContentType)}>
                  <option value="SHORTS">{text(CONTENT_TYPE, 'SHORTS')}</option>
                  <option value="LONGFORM">{text(CONTENT_TYPE, 'LONGFORM')}</option>
                </Select>
              </FormField>
            </FormSection>
            {shorts ? (
              <FormSection title="소재 발굴" description="후보를 만들고 중복·금지 소재·근거 없는 주장을 거른 뒤 6개 항목 100점 기준으로 평가합니다.">
                <fieldset className="grid gap-2">
                  <legend className="mb-1 text-sm font-medium">선정 방식</legend>
                  {(['AUTO', 'MANUAL'] as const).map((value) => (
                    <label key={value} className="flex items-start gap-2.5 rounded-md border border-line px-3 py-2.5">
                      <input
                        type="radio"
                        name="selectionMode"
                        value={value}
                        checked={mode === value}
                        onChange={() => setMode(value)}
                        className="mt-1"
                      />
                      <span>
                        <span className="block font-medium">{SELECTION_MODE[value].text}</span>
                        <span className="block text-sm text-muted">{SELECTION_MODE[value].help}</span>
                      </span>
                    </label>
                  ))}
                </fieldset>
                <FormField label="후보 수" hint={`${MIN_CANDIDATES}~${MAX_CANDIDATES}개, 기본 ${DEFAULT_CANDIDATES}개`} error={errors.candidateCount}>
                  <Select value={String(count)} onChange={(e) => setCount(Number.parseInt(e.target.value, 10))}>
                    {COUNTS.map((value) => (
                      <option key={value} value={value}>
                        {value}개
                      </option>
                    ))}
                  </Select>
                </FormField>
              </FormSection>
            ) : (
              <InlineNotice>롱폼은 기존 고정 단계 경로로 제작하며 소재 발굴 옵션을 사용하지 않습니다.</InlineNotice>
            )}
            <div className="flex justify-end">
              <Button type="submit" variant="risk" icon={<Clapperboard aria-hidden className="size-4" />}>
                제작 시작…
              </Button>
            </div>
          </form>
        </Card>
      </div>
      <ConfirmDialog
        open={confirming}
        title="제작을 시작할까요?"
        confirmLabel="제작 시작"
        tone="risk"
        busy={start.isPending}
        onConfirm={() => start.mutate(body)}
        onCancel={() => setConfirming(false)}
      >
        <p>
          <strong>{channelName || '선택한 채널'}</strong>의 {text(CONTENT_TYPE, contentType)} 제작 요청을 만들고 소재 발굴부터 실행합니다.
        </p>
        {shorts && (
          <p className="text-sm">
            {SELECTION_MODE[mode].text} · 후보 {count}개
          </p>
        )}
        <p className="text-sm text-muted">
          모든 단계는 같은 제작 요청의 비용 한도 안에서만 실행됩니다. 실제 AI 서비스가 연동되면 유료 호출로 이어질 수 있습니다.
        </p>
        {start.error && <ErrorState error={start.error} compact />}
      </ConfirmDialog>
    </>
  )
}

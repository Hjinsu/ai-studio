import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { budgetUsage } from '../api/admin'
import { Field, QueryView } from '../ui/components'
import { formatInstant } from '../ui/format'
import { BucketTable } from './BucketTable'

/** 현재 기간 예산 사용량. 채널 지정 시 SYSTEM + 해당 채널 정책(각 bucket 별도 표시, 합산하지 않음). */
export function UsagePage() {
  const [input, setInput] = useState('')
  const [channelId, setChannelId] = useState<string | undefined>(undefined)
  const usage = useQuery({ queryKey: ['usage', channelId ?? ''], queryFn: () => budgetUsage(channelId) })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setChannelId(input.trim() === '' ? undefined : input.trim())
  }
  return (
    <section aria-labelledby="usage-heading">
      <h1 id="usage-heading">예산 사용량</h1>
      <form onSubmit={submit} className="filters">
        <Field label="채널 ID(선택)">
          <input pattern="[1-9][0-9]{0,18}" value={input} onChange={(e) => setInput(e.target.value)} />
        </Field>
        <button type="submit">조회</button>
      </form>
      <QueryView query={usage}>
        {(data) => (
          <>
            <p className="muted">기준 시각 {formatInstant(data.at)} · 각 정책의 bucket은 서로 합산하지 않습니다.</p>
            <BucketTable buckets={data.buckets} empty="적용되는 활성 정책이 없습니다." />
          </>
        )}
      </QueryView>
    </section>
  )
}

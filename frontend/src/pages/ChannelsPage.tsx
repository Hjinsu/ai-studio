import { useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { createChannel, listChannels } from '../api/admin'
import { Empty, ErrorAlert, Field, Pager, QueryView } from '../ui/components'
import { browserTimeZone, formatInstant } from '../ui/format'

const STATUS_LABEL: Record<string, string> = { ACTIVE: '활성', PAUSED: '일시정지', ARCHIVED: '보관' }

export function ChannelsPage() {
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const channels = useQuery({
    queryKey: ['channels', status, page],
    queryFn: () => listChannels({ status: status || undefined, page, size: 20 }),
    placeholderData: keepPreviousData,
  })
  return (
    <section aria-labelledby="channels-heading">
      <h1 id="channels-heading">채널</h1>
      <Field label="상태">
        <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
          <option value="">전체</option>
          <option value="ACTIVE">활성</option>
          <option value="PAUSED">일시정지</option>
          <option value="ARCHIVED">보관</option>
        </select>
      </Field>
      <QueryView query={channels}>
        {(data) =>
          data.items.length === 0 ? (
            <Empty>채널이 없습니다.</Empty>
          ) : (
            <>
              <table>
                <thead>
                  <tr>
                    <th scope="col">이름</th>
                    <th scope="col">언어</th>
                    <th scope="col">시간대</th>
                    <th scope="col">상태</th>
                    <th scope="col">설정 revision</th>
                    <th scope="col">수정 시각(채널 시간대)</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((channel) => (
                    <tr key={channel.id}>
                      <td>
                        <Link to={`/channels/${channel.id}`}>{channel.name}</Link>
                      </td>
                      <td>{channel.language}</td>
                      <td>{channel.timezone}</td>
                      <td>{STATUS_LABEL[channel.status] ?? channel.status}</td>
                      <td>r{channel.currentConfigRevisionNo}</td>
                      <td>{formatInstant(channel.updatedAt, channel.timezone)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
            </>
          )
        }
      </QueryView>
      <CreateChannelForm />
    </section>
  )
}

function CreateChannelForm() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [language, setLanguage] = useState('en')
  const [timezone, setTimezone] = useState(browserTimeZone())
  const create = useMutation({
    mutationFn: () => createChannel({ name, language, timezone }),
    onSuccess: async (channel) => {
      await queryClient.invalidateQueries({ queryKey: ['channels'] })
      await navigate(`/channels/${channel.id}`)
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    create.mutate()
  }
  return (
    <form onSubmit={submit} className="card" aria-labelledby="create-channel-heading">
      <h2 id="create-channel-heading">채널 등록</h2>
      <p className="muted">설정은 기본값으로 생성되며 상세 화면에서 새 revision으로 변경합니다.</p>
      <Field label="이름">
        <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="언어" hint="예: en, ko, en-US">
        <input required pattern="[a-z]{2,3}(-[A-Za-z0-9]{2,8})*" value={language}
          onChange={(e) => setLanguage(e.target.value)} />
      </Field>
      <Field label="시간대(IANA)" hint="예: Asia/Seoul, America/New_York">
        <input required maxLength={64} value={timezone} onChange={(e) => setTimezone(e.target.value)} />
      </Field>
      <button type="submit" className="primary" disabled={create.isPending}>등록</button>
      <ErrorAlert error={create.error} />
    </form>
  )
}

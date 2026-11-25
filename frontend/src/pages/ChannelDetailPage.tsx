import { useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import {
  changeChannelStatus,
  createChannelConfig,
  currentChannelConfig,
  getChannel,
  listChannelConfigs,
  updateChannel,
  type Channel,
  type ChannelConfig,
  type ChannelConfigSettings,
} from '../api/admin'
import { ConfirmDialog, Empty, ErrorAlert, Field, Pager, QueryView } from '../ui/components'
import { formatInstant } from '../ui/format'
import { ChannelSettingsForm } from './ChannelSettingsForm'

type StatusAction = 'pause' | 'resume' | 'archive'

const ACTION_LABEL: Record<StatusAction, string> = { pause: '일시정지', resume: '재개', archive: '보관' }

export function ChannelDetailPage() {
  const { channelId = '' } = useParams()
  const channel = useQuery({ queryKey: ['channel', channelId], queryFn: () => getChannel(channelId) })
  return (
    <section aria-labelledby="channel-heading">
      <QueryView query={channel}>
        {(data) => (
          <>
            <h1 id="channel-heading">
              {data.name} <span className={`badge ${data.status.toLowerCase()}`}>{data.status}</span>
            </h1>
            <dl className="meta">
              <dt>ID</dt>
              <dd>{data.id}</dd>
              <dt>언어</dt>
              <dd>{data.language}</dd>
              <dt>시간대</dt>
              <dd>{data.timezone}</dd>
              <dt>버전</dt>
              <dd>{data.version}</dd>
              <dt>생성</dt>
              <dd>{formatInstant(data.createdAt, data.timezone)}</dd>
              {data.archivedAt && (
                <>
                  <dt>보관</dt>
                  <dd>{formatInstant(data.archivedAt, data.timezone)}</dd>
                </>
              )}
            </dl>
            <StatusActions channel={data} />
            {data.status !== 'ARCHIVED' && <EditChannelForm key={data.version} channel={data} />}
            <ConfigSection channel={data} />
          </>
        )}
      </QueryView>
    </section>
  )
}

function useRefreshChannel(channelId: string) {
  const queryClient = useQueryClient()
  return async () => {
    await queryClient.invalidateQueries({ queryKey: ['channel', channelId] })
    await queryClient.invalidateQueries({ queryKey: ['channels'] })
    await queryClient.invalidateQueries({ queryKey: ['channel-config', channelId] })
  }
}

function StatusActions({ channel }: { channel: Channel }) {
  const refresh = useRefreshChannel(channel.id)
  const [pending, setPending] = useState<StatusAction | null>(null)
  const change = useMutation({
    mutationFn: (action: StatusAction) => changeChannelStatus(channel.id, action, channel.version),
    onSuccess: async () => {
      setPending(null)
      await refresh()
    },
  })
  if (channel.status === 'ARCHIVED') {
    return <p className="muted">보관된 채널은 변경할 수 없습니다(비용·이력은 보존).</p>
  }
  const actions: StatusAction[] = channel.status === 'ACTIVE' ? ['pause', 'archive'] : ['resume', 'archive']
  return (
    <div className="actions">
      {actions.map((action) => (
        <button key={action} type="button" className={action === 'archive' ? 'danger' : undefined}
          onClick={() => { change.reset(); setPending(action) }}>
          {ACTION_LABEL[action]}
        </button>
      ))}
      <ConfirmDialog
        open={pending !== null}
        title={pending ? `채널을 ${ACTION_LABEL[pending]}할까요?` : ''}
        confirmLabel={pending ? ACTION_LABEL[pending] : ''}
        danger={pending === 'archive'}
        busy={change.isPending}
        onConfirm={() => pending && change.mutate(pending)}
        onCancel={() => setPending(null)}
      >
        {pending === 'archive' && <p>보관은 되돌릴 수 없습니다. 신규 예약이 차단되고 기존 정산은 계속 허용됩니다.</p>}
        {pending === 'pause' && <p>일시정지 중에는 이 채널의 신규 비용 예약이 차단됩니다.</p>}
        <ErrorAlert error={change.error} />
      </ConfirmDialog>
    </div>
  )
}

function EditChannelForm({ channel }: { channel: Channel }) {
  const refresh = useRefreshChannel(channel.id)
  const [name, setName] = useState(channel.name)
  const [language, setLanguage] = useState(channel.language)
  const [timezone, setTimezone] = useState(channel.timezone)
  const update = useMutation({
    mutationFn: () => {
      const body: Parameters<typeof updateChannel>[1] = { version: channel.version }
      if (name !== channel.name) body.name = name
      if (language !== channel.language) body.language = language
      if (timezone !== channel.timezone) body.timezone = timezone
      return updateChannel(channel.id, body)
    },
    onSuccess: refresh,
  })
  const unchanged = name === channel.name && language === channel.language && timezone === channel.timezone
  const submit = (event: FormEvent) => {
    event.preventDefault()
    update.mutate()
  }
  return (
    <form onSubmit={submit} className="card" aria-labelledby="edit-channel-heading">
      <h2 id="edit-channel-heading">기본 정보 수정</h2>
      <Field label="이름">
        <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="언어">
        <input required value={language} onChange={(e) => setLanguage(e.target.value)} />
      </Field>
      <Field label="시간대(IANA)">
        <input required maxLength={64} value={timezone} onChange={(e) => setTimezone(e.target.value)} />
      </Field>
      <button type="submit" className="primary" disabled={unchanged || update.isPending}>저장</button>
      <ErrorAlert error={update.error} />
    </form>
  )
}

function settingsOf(config: ChannelConfig): ChannelConfigSettings {
  return {
    defaultContentType: config.defaultContentType,
    shortsMinSeconds: config.shortsMinSeconds,
    shortsMaxSeconds: config.shortsMaxSeconds,
    longformMinSeconds: config.longformMinSeconds,
    longformMaxSeconds: config.longformMaxSeconds,
    contentStrategy: config.contentStrategy ?? null,
    targetAudience: config.targetAudience ?? null,
    narrationStyle: config.narrationStyle ?? null,
    visualStyle: config.visualStyle ?? null,
    syntheticDisclosureDefault: config.syntheticDisclosureDefault,
    providerOptions: config.providerOptions ?? null,
  }
}

function ConfigSection({ channel }: { channel: Channel }) {
  const refresh = useRefreshChannel(channel.id)
  const [page, setPage] = useState(0)
  const current = useQuery({ queryKey: ['channel-config', channel.id, 'current'],
    queryFn: () => currentChannelConfig(channel.id) })
  const history = useQuery({
    queryKey: ['channel-config', channel.id, 'history', page],
    queryFn: () => listChannelConfigs(channel.id, { page, size: 10 }),
    placeholderData: keepPreviousData,
  })
  const create = useMutation({
    mutationFn: ({ base, settings, note }: { base: number; settings: ChannelConfigSettings; note: string }) =>
      createChannelConfig(channel.id, { baseRevisionNo: base, settings, ...(note ? { changeNote: note } : {}) }),
    onSuccess: refresh,
  })
  return (
    <>
      <h2>채널 설정</h2>
      <QueryView query={current}>
        {(config) => (
          <>
            <ConfigView config={config} timezone={channel.timezone} />
            {channel.status !== 'ARCHIVED' && (
              <details className="card">
                <summary>새 설정 revision 만들기 (기준 r{config.revisionNo})</summary>
                <ChannelSettingsForm
                  key={config.revisionNo}
                  initial={settingsOf(config)}
                  disabled={create.isPending}
                  submitLabel="revision 생성"
                  onSubmit={(settings, note) => create.mutate({ base: config.revisionNo, settings, note })}
                />
                <ErrorAlert error={create.error} />
              </details>
            )}
          </>
        )}
      </QueryView>
      <h2>설정 이력</h2>
      <QueryView query={history}>
        {(data) =>
          data.items.length === 0 ? (
            <Empty />
          ) : (
            <>
              <ul className="history">
                {data.items.map((config) => (
                  <li key={config.revisionNo}>
                    <details>
                      <summary>
                        r{config.revisionNo} · {formatInstant(config.createdAt, channel.timezone)} · 작성자 #
                        {config.createdBy}
                        {config.changeNote && <> · {config.changeNote}</>}
                      </summary>
                      <ConfigView config={config} timezone={channel.timezone} />
                    </details>
                  </li>
                ))}
              </ul>
              <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
            </>
          )
        }
      </QueryView>
    </>
  )
}

function ConfigView({ config, timezone }: { config: ChannelConfig; timezone: string }) {
  return (
    <dl className="meta">
      <dt>revision</dt>
      <dd>r{config.revisionNo} ({formatInstant(config.createdAt, timezone)})</dd>
      <dt>기본 유형</dt>
      <dd>{config.defaultContentType}</dd>
      <dt>Shorts</dt>
      <dd>{config.shortsMinSeconds}–{config.shortsMaxSeconds}초</dd>
      <dt>Longform</dt>
      <dd>{config.longformMinSeconds}–{config.longformMaxSeconds}초</dd>
      <dt>전략</dt>
      <dd>{config.contentStrategy ?? '—'}</dd>
      <dt>대상</dt>
      <dd>{config.targetAudience ?? '—'}</dd>
      <dt>내레이션</dt>
      <dd>{config.narrationStyle ?? '—'}</dd>
      <dt>시각</dt>
      <dd>{config.visualStyle ?? '—'}</dd>
      <dt>합성 고지 기본</dt>
      <dd>{config.syntheticDisclosureDefault ? '예' : '아니오'}</dd>
      <dt>provider 옵션</dt>
      <dd>{config.providerOptions ? <code>{JSON.stringify(config.providerOptions)}</code> : '—'}</dd>
    </dl>
  )
}

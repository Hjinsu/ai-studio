import { useState, type FormEvent, type ReactNode } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router'
import { Archive, Pause, Pencil, Play, Plus } from 'lucide-react'
import {
  changeChannelStatus,
  createChannelConfig,
  currentChannelConfig,
  getChannel,
  listChannelConfigs,
  updateChannel,
  type Channel,
  type ChannelConfig,
} from '../api/admin'
import { fieldErrors } from '../lib/errors'
import { CHANNEL_STATUS, label } from '../lib/labels'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { ConfirmDialog, Dialog } from '../ui/Dialog'
import { DateTime } from '../ui/Display'
import { FormField, Input, Select, TimeZoneInput } from '../ui/Form'
import { Tabs } from '../ui/Overlay'
import { PageHeader } from '../ui/PageHeader'
import { RevisionHistory } from '../ui/RevisionHistory'
import { ErrorState, InlineNotice, QueryView } from '../ui/States'
import { LabelBadge } from '../ui/StatusBadge'
import { Pagination } from '../ui/DataTable'
import { useToast } from '../ui/toast-context'
import { ChannelSettingsFields } from './channels/ChannelSettingsFields'
import { SETTING_FIELDS, diffSettings, draftOf, settingsOf, toSettings } from './channels/settings'

type StatusAction = 'pause' | 'resume' | 'archive'

const ACTION: Record<StatusAction, { label: string; title: string; body: string }> = {
  pause: { label: '일시정지', title: '채널을 일시정지할까요?', body: '일시정지 중에는 이 채널의 신규 비용 예약이 차단됩니다. 진행 중인 정산은 계속됩니다. 언제든 다시 운영할 수 있습니다.' },
  resume: { label: '운영 재개', title: '채널 운영을 재개할까요?', body: '이 채널의 신규 비용 예약이 다시 가능해집니다(다른 예산 조건은 그대로 적용).' },
  archive: { label: '보관', title: '채널을 보관할까요?', body: '보관은 되돌릴 수 없습니다. 신규 예약과 설정 변경이 막히며, 지금까지의 비용·설정 이력은 그대로 보존됩니다.' },
}

export function ChannelDetailPage() {
  const { channelId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'overview'
  const channel = useQuery({ queryKey: ['channel', channelId], queryFn: () => getChannel(channelId) })
  return (
    <QueryView query={channel} rows={5}>
      {(data) => (
        <>
          <PageHeader
            title={data.name}
            meta={<LabelBadge value={label(CHANNEL_STATUS, data.status)} />}
            description={`언어 ${data.language} · 시간대 ${data.timezone}`}
            actions={<StatusActions channel={data} />}
          />
          <Tabs
            label="채널 정보"
            value={tab}
            onValueChange={(value) => setParams(value === 'overview' ? {} : { tab: value }, { replace: true })}
            items={[
              { value: 'overview', label: '개요', content: <Overview channel={data} /> },
              { value: 'settings', label: '채널 설정', content: <CurrentSettings channel={data} /> },
              { value: 'history', label: '설정 이력', content: <ConfigHistory channel={data} /> },
            ]}
          />
        </>
      )}
    </QueryView>
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
  const toast = useToast()
  const [pending, setPending] = useState<StatusAction | null>(null)
  const change = useMutation({
    mutationFn: (action: StatusAction) => changeChannelStatus(channel.id, action, channel.version),
    onSuccess: async (_result, action) => {
      setPending(null)
      toast.success(`채널을 ${ACTION[action].label}했습니다.`)
      await refresh()
    },
  })
  if (channel.status === 'ARCHIVED') {
    return null
  }
  const actions: StatusAction[] = channel.status === 'ACTIVE' ? ['pause', 'archive'] : ['resume', 'archive']
  const icons = { pause: Pause, resume: Play, archive: Archive }
  return (
    <>
      {actions.map((action) => {
        const Icon = icons[action]
        return (
          <Button key={action} variant={action === 'archive' ? 'ghost' : 'secondary'} icon={<Icon aria-hidden className="size-4" />}
            onClick={() => { change.reset(); setPending(action) }}>
            {ACTION[action].label}
          </Button>
        )
      })}
      <ConfirmDialog
        open={pending !== null}
        title={pending ? ACTION[pending].title : ''}
        confirmLabel={pending ? ACTION[pending].label : ''}
        tone={pending === 'resume' ? 'primary' : 'danger'}
        busy={change.isPending}
        onConfirm={() => pending && change.mutate(pending)}
        onCancel={() => setPending(null)}
      >
        <p>{pending && ACTION[pending].body}</p>
        {change.error && <ErrorState error={change.error} compact />}
      </ConfirmDialog>
    </>
  )
}

function Overview({ channel }: { channel: Channel }) {
  const [editing, setEditing] = useState(false)
  return (
    <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
      <Card
        title="기본 정보"
        actions={channel.status !== 'ARCHIVED' && (
          <Button size="sm" icon={<Pencil aria-hidden className="size-3.5" />} onClick={() => setEditing(true)}>
            수정
          </Button>
        )}
      >
        <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
          <Item label="채널명" value={channel.name} />
          <Item label="운영 상태" value={<LabelBadge value={label(CHANNEL_STATUS, channel.status)} />} />
          <Item label="언어" value={channel.language} />
          <Item label="시간대" value={channel.timezone} />
          <Item label="등록" value={<DateTime value={channel.createdAt} timeZone={channel.timezone} />} />
          <Item label="최근 변경" value={<DateTime value={channel.updatedAt} timeZone={channel.timezone} />} />
          {channel.archivedAt && <Item label="보관" value={<DateTime value={channel.archivedAt} timeZone={channel.timezone} />} />}
        </dl>
      </Card>
      <Card title="상태 안내">
        <p className="text-sm text-muted">
          {channel.status === 'ACTIVE' && '운영 중인 채널은 예산 조건 안에서 비용을 예약할 수 있습니다.'}
          {channel.status === 'PAUSED' && '일시정지된 채널은 신규 비용 예약이 차단됩니다. 기존 정산은 계속됩니다.'}
          {channel.status === 'ARCHIVED' && '보관된 채널은 변경할 수 없으며 비용·설정 이력만 보존됩니다.'}
        </p>
      </Card>
      {editing && <EditChannelDialog channel={channel} onClose={() => setEditing(false)} />}
    </div>
  )
}

function Item({ label: name, value }: { label: string; value: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-muted">{name}</dt>
      <dd className="mt-0.5 break-words">{value}</dd>
    </div>
  )
}

function EditChannelDialog({ channel, onClose }: { channel: Channel; onClose: () => void }) {
  const refresh = useRefreshChannel(channel.id)
  const toast = useToast()
  const [name, setName] = useState(channel.name)
  const [language, setLanguage] = useState(channel.language)
  const [timezone, setTimezone] = useState(channel.timezone)
  const update = useMutation({
    mutationFn: () => {
      const body: Parameters<typeof updateChannel>[1] = { version: channel.version }
      if (name !== channel.name) body.name = name.trim()
      if (language !== channel.language) body.language = language.trim()
      if (timezone !== channel.timezone) body.timezone = timezone.trim()
      return updateChannel(channel.id, body)
    },
    onSuccess: async () => {
      toast.success('기본 정보를 저장했습니다.')
      await refresh()
      onClose()
    },
  })
  const errors = fieldErrors(update.error)
  const unchanged = name === channel.name && language === channel.language && timezone === channel.timezone
  const submit = (event: FormEvent) => {
    event.preventDefault()
    update.mutate()
  }
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && !update.isPending && onClose()}
      title="기본 정보 수정"
      footer={
        <>
          <Button onClick={onClose} disabled={update.isPending}>취소</Button>
          <Button type="submit" form="edit-channel-form" variant="primary" loading={update.isPending} disabled={unchanged}>
            저장
          </Button>
        </>
      }
    >
      <form id="edit-channel-form" onSubmit={submit} className="grid gap-4" aria-label="기본 정보 수정">
        <FormField label="채널명" error={errors.name}>
          <Input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </FormField>
        <FormField label="언어" hint="예: ko, en, en-US" error={errors.language}>
          <Input required value={language} onChange={(e) => setLanguage(e.target.value)} />
        </FormField>
        <FormField label="시간대" error={errors.timezone}>
          <TimeZoneInput required value={timezone} onChange={(e) => setTimezone(e.target.value)} />
        </FormField>
        {update.error && <ErrorState error={update.error} compact />}
      </form>
    </Dialog>
  )
}

function CurrentSettings({ channel }: { channel: Channel }) {
  const [editing, setEditing] = useState(false)
  const current = useQuery({ queryKey: ['channel-config', channel.id, 'current'], queryFn: () => currentChannelConfig(channel.id) })
  return (
    <QueryView query={current}>
      {(config) => (
        <Card
          title={`현재 설정 · r${config.revisionNo}`}
          description={<>적용 <DateTime value={config.createdAt} timeZone={channel.timezone} /> · 관리자 #{config.createdBy}</>}
          actions={channel.status !== 'ARCHIVED' && (
            <Button variant="primary" size="sm" icon={<Plus aria-hidden className="size-3.5" />} onClick={() => setEditing(true)}>
              새 설정 revision
            </Button>
          )}
        >
          <p className="mb-4 text-sm text-muted">설정을 바꾸면 기존 revision은 그대로 남고 새 revision이 추가됩니다.</p>
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
            {SETTING_FIELDS.map((field) => (
              <Item key={field.key} label={field.label} value={field.format(settingsOf(config))} />
            ))}
          </dl>
          {editing && <RevisionDialog channel={channel} base={config} onClose={() => setEditing(false)} />}
        </Card>
      )}
    </QueryView>
  )
}

function RevisionDialog({ channel, base, onClose }: { channel: Channel; base: ChannelConfig; onClose: () => void }) {
  const refresh = useRefreshChannel(channel.id)
  const toast = useToast()
  const [draft, setDraft] = useState(draftOf(settingsOf(base)))
  const [changeNote, setChangeNote] = useState('')
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({})
  const create = useMutation({
    mutationFn: (settings: ReturnType<typeof settingsOf>) =>
      createChannelConfig(channel.id, { baseRevisionNo: base.revisionNo, settings, ...(changeNote.trim() ? { changeNote: changeNote.trim() } : {}) }),
    onSuccess: async (config) => {
      toast.success(`설정 r${config.revisionNo}을 저장했습니다.`)
      await refresh()
      onClose()
    },
  })
  const errors = { ...fieldErrors(create.error), ...localErrors }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const result = toSettings(draft)
    setLocalErrors(result.errors)
    if (result.settings) create.mutate(result.settings)
  }
  const preview = toSettings(draft).settings
  const changes = preview ? diffSettings(settingsOf(base), preview) : []
  return (
    <Dialog
      open
      size="lg"
      onOpenChange={(open) => !open && !create.isPending && onClose()}
      title={`새 설정 revision (기준 r${base.revisionNo})`}
      description="다른 사람이 먼저 새 revision을 만들었다면 저장이 거부됩니다. 새로고침 후 다시 작성하세요."
      footer={
        <>
          <span className="mr-auto self-center text-sm text-muted">
            {preview === undefined ? '입력 오류 확인 필요' : changes.length === 0 ? '변경된 항목 없음' : `변경 ${changes.length}개 항목`}
          </span>
          <Button onClick={onClose} disabled={create.isPending}>취소</Button>
          <Button type="submit" form="revision-form" variant="primary" loading={create.isPending} disabled={preview !== undefined && changes.length === 0}>
            revision 저장
          </Button>
        </>
      }
    >
      <form id="revision-form" onSubmit={submit} className="grid gap-6" aria-label="새 설정 revision">
        <ChannelSettingsFields draft={draft} onChange={setDraft} errors={errors} />
        <FormField label="변경 메모" optional hint="무엇을 왜 바꿨는지 이력에 남깁니다." error={errors.changeNote}>
          <Input maxLength={500} value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
        </FormField>
        {create.error && <ErrorState error={create.error} compact />}
      </form>
    </Dialog>
  )
}

function ConfigHistory({ channel }: { channel: Channel }) {
  const [page, setPage] = useState(0)
  const history = useQuery({
    queryKey: ['channel-config', channel.id, 'history', page],
    queryFn: () => listChannelConfigs(channel.id, { page, size: 50 }),
    placeholderData: keepPreviousData,
  })
  return (
    <QueryView query={history}>
      {(data) => {
        const items = data.items
        return (
          <div className="grid gap-6 lg:grid-cols-[1fr_22rem]">
            <Card title="설정 이력" description="최신 revision이 위에 있습니다. 각 항목은 직전 revision 대비 변경 내용입니다.">
              <RevisionHistory
                label="채널 설정 이력"
                entries={items.map((config, index) => {
                  const previous = items[index + 1]
                  return {
                    key: String(config.revisionNo),
                    title: `r${config.revisionNo}`,
                    current: config.revisionNo === channel.currentConfigRevisionNo,
                    meta: (
                      <>
                        <DateTime value={config.createdAt} timeZone={channel.timezone} /> · 관리자 #{config.createdBy}
                      </>
                    ),
                    note: config.changeNote,
                    changes: previous ? diffSettings(settingsOf(previous), settingsOf(config)) : undefined,
                    body: !previous && config.revisionNo === 1 ? <p className="mt-1 text-sm text-muted">채널 등록 시 처음 저장된 설정입니다.</p> : undefined,
                  }
                })}
              />
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
            </Card>
            <CompareRevisions configs={items} />
          </div>
        )
      }}
    </QueryView>
  )
}

/** 불러온 revision 중 두 개를 골라 비교. */
function CompareRevisions({ configs }: { configs: ChannelConfig[] }) {
  const [left, setLeft] = useState(configs[1]?.revisionNo ?? configs[0]?.revisionNo ?? 0)
  const [right, setRight] = useState(configs[0]?.revisionNo ?? 0)
  if (configs.length < 2) {
    return (
      <Card title="revision 비교">
        <InlineNotice>비교하려면 revision이 2개 이상 있어야 합니다.</InlineNotice>
      </Card>
    )
  }
  const a = configs.find((c) => c.revisionNo === left)
  const b = configs.find((c) => c.revisionNo === right)
  const changes = a && b ? diffSettings(settingsOf(a), settingsOf(b)) : []
  return (
    <Card title="revision 비교" description="두 revision의 설정 차이를 봅니다.">
      <div className="grid grid-cols-2 gap-3">
        <FormField label="이전">
          <Select value={left} onChange={(e) => setLeft(Number(e.target.value))}>
            {configs.map((c) => <option key={c.revisionNo} value={c.revisionNo}>r{c.revisionNo}</option>)}
          </Select>
        </FormField>
        <FormField label="이후">
          <Select value={right} onChange={(e) => setRight(Number(e.target.value))}>
            {configs.map((c) => <option key={c.revisionNo} value={c.revisionNo}>r{c.revisionNo}</option>)}
          </Select>
        </FormField>
      </div>
      <div className="mt-4">
        {changes.length === 0 ? (
          <p className="text-sm text-muted">두 revision의 설정이 같습니다.</p>
        ) : (
          <dl className="grid gap-2.5 text-sm">
            {changes.map((change) => (
              <div key={change.field}>
                <dt className="text-muted">{change.field}</dt>
                <dd className="break-words">
                  <span className="text-muted line-through">{change.before}</span>
                  <span aria-hidden className="mx-1.5 text-muted">→</span>
                  <span className="sr-only">에서 </span>
                  <span className="font-medium">{change.after}</span>
                </dd>
              </div>
            ))}
          </dl>
        )}
      </div>
    </Card>
  )
}

import { useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { Plus, Tv } from 'lucide-react'
import { createChannel, currentChannelConfig, listChannels, type Channel } from '../api/admin'
import { fieldErrors } from '../lib/errors'
import { CHANNEL_STATUS, CONTENT_TYPE, label, text } from '../lib/labels'
import { browserTimeZone } from '../lib/time'
import { Button, LinkButton } from '../ui/Button'
import { DataTable, Pagination, type Column } from '../ui/DataTable'
import { Dialog } from '../ui/Dialog'
import { DateTime } from '../ui/Display'
import { FormField, FormSection, Input, Select, TimeZoneInput } from '../ui/Form'
import { PageHeader } from '../ui/PageHeader'
import { EmptyState, ErrorState, QueryView } from '../ui/States'
import { LabelBadge } from '../ui/StatusBadge'
import { useToast } from '../ui/toast-context'
import { ChannelSettingsFields } from './channels/ChannelSettingsFields'
import { DEFAULT_SETTINGS, draftOf, toSettings } from './channels/settings'

export function ChannelsPage() {
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const channels = useQuery({
    queryKey: ['channels', status, page],
    queryFn: () => listChannels({ status: status || undefined, page, size: 20 }),
    placeholderData: keepPreviousData,
  })
  const columns: Column<Channel>[] = [
    {
      key: 'name',
      header: '채널명',
      cell: (c) => (
        <Link to={`/channels/${c.id}`} className="font-medium hover:text-primary hover:underline">
          {c.name}
        </Link>
      ),
    },
    { key: 'language', header: '언어', cell: (c) => c.language },
    { key: 'timezone', header: '시간대', cell: (c) => c.timezone, hideBelow: 'lg' },
    { key: 'type', header: '기본 콘텐츠 유형', cell: (c) => <ConfigCell channel={c} field="type" /> },
    { key: 'status', header: '운영 상태', cell: (c) => <LabelBadge value={label(CHANNEL_STATUS, c.status)} /> },
    { key: 'config', header: '최근 설정 변경', cell: (c) => <ConfigCell channel={c} field="changed" />, hideBelow: 'md' },
    {
      key: 'actions',
      header: '작업',
      align: 'right',
      cell: (c) => (
        <div className="flex justify-end gap-1.5">
          <LinkButton to={`/channels/${c.id}`} size="sm" variant="ghost">
            상세
          </LinkButton>
          {c.status !== 'ARCHIVED' && (
            <LinkButton to={`/channels/${c.id}?tab=settings`} size="sm">
              설정 수정
            </LinkButton>
          )}
        </div>
      ),
    },
  ]
  return (
    <>
      <PageHeader
        title="채널 관리"
        description="콘텐츠를 만들 채널과 채널별 제작 설정을 관리합니다. 채널은 삭제하지 않고 보관합니다."
        actions={
          <Button variant="primary" icon={<Plus aria-hidden className="size-4" />} onClick={() => setCreating(true)}>
            채널 등록
          </Button>
        }
      />
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <FormField label="운영 상태" className="w-44">
          <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">전체</option>
            <option value="ACTIVE">운영 중</option>
            <option value="PAUSED">일시정지</option>
            <option value="ARCHIVED">보관됨</option>
          </Select>
        </FormField>
      </div>
      <QueryView query={channels}>
        {(data) =>
          data.items.length === 0 ? (
            <EmptyState
              icon={<Tv className="size-6" />}
              title={status ? '조건에 맞는 채널이 없습니다' : '등록된 채널이 없습니다'}
              description={status ? '운영 상태 필터를 바꿔 보세요.' : '채널을 등록하면 채널별 설정과 예산 정책을 만들 수 있습니다.'}
              action={!status && <Button variant="primary" onClick={() => setCreating(true)}>채널 등록</Button>}
            />
          ) : (
            <>
              <DataTable caption="채널 목록" columns={columns} rows={data.items} rowKey={(c) => c.id} />
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />
            </>
          )
        }
      </QueryView>
      <CreateChannelDialog open={creating} onOpenChange={setCreating} />
    </>
  )
}

/** 채널 목록 API에는 설정이 없어 행마다 현재 설정을 조회한다(P-T02-03: 목록 요약 필드 제안). */
function ConfigCell({ channel, field }: { channel: Channel; field: 'type' | 'changed' }) {
  const config = useQuery({
    queryKey: ['channel-config', channel.id, 'current'],
    queryFn: () => currentChannelConfig(channel.id),
    staleTime: 30_000,
  })
  if (config.isPending) return <span className="text-muted">…</span>
  if (config.isError) return <span className="text-muted">확인 불가</span>
  return field === 'type' ? (
    <>{text(CONTENT_TYPE, config.data.defaultContentType)}</>
  ) : (
    <span className="text-sm">
      <DateTime value={config.data.createdAt} timeZone={channel.timezone} showZone={false} />
      <span className="ml-1 text-xs text-muted">r{config.data.revisionNo}</span>
    </span>
  )
}

function CreateChannelDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const toast = useToast()
  const [name, setName] = useState('')
  const [language, setLanguage] = useState('ko')
  const [timezone, setTimezone] = useState(browserTimeZone())
  const [draft, setDraft] = useState(draftOf(DEFAULT_SETTINGS))
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({})
  const create = useMutation({
    mutationFn: (body: Parameters<typeof createChannel>[0]) => createChannel(body),
    onSuccess: async (channel) => {
      toast.success(`채널 "${channel.name}"을 등록했습니다.`)
      await queryClient.invalidateQueries({ queryKey: ['channels'] })
      onOpenChange(false)
      await navigate(`/channels/${channel.id}`)
    },
  })
  const errors = { ...fieldErrors(create.error), ...localErrors }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const result = toSettings(draft)
    setLocalErrors(result.errors)
    if (result.settings) {
      create.mutate({ name: name.trim(), language: language.trim(), timezone: timezone.trim(), initialConfig: result.settings })
    }
  }
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => !create.isPending && onOpenChange(next)}
      title="채널 등록"
      description="기본 정보와 첫 제작 설정(revision 1)을 함께 저장합니다. 설정은 이후 새 revision으로 바꿀 수 있습니다."
      size="lg"
      footer={
        <>
          <Button onClick={() => onOpenChange(false)} disabled={create.isPending}>
            취소
          </Button>
          <Button type="submit" form="create-channel-form" variant="primary" loading={create.isPending}>
            채널 등록
          </Button>
        </>
      }
    >
      <form id="create-channel-form" onSubmit={submit} className="grid gap-6" aria-label="채널 등록">
        <FormSection title="기본 정보">
          <FormField label="채널명" error={errors.name}>
            <Input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          </FormField>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label="언어" hint="언어 코드. 예: ko, en, en-US" error={errors.language}>
              <Input required pattern="[a-z]{2,3}(-[A-Za-z0-9]{2,8})*" value={language} onChange={(e) => setLanguage(e.target.value)} />
            </FormField>
            <FormField label="시간대" hint="채널 화면의 시각 표시 기준. 예: Asia/Seoul" error={errors.timezone}>
              <TimeZoneInput required value={timezone} onChange={(e) => setTimezone(e.target.value)} />
            </FormField>
          </div>
        </FormSection>
        <ChannelSettingsFields draft={draft} onChange={setDraft} errors={errors} />
        {create.error && !Object.keys(fieldErrors(create.error)).length && <ErrorState error={create.error} compact />}
      </form>
    </Dialog>
  )
}

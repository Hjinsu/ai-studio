import { useQuery } from '@tanstack/react-query'
import { Download, Film, FileText, Image as ImageIcon } from 'lucide-react'
import { ApiError } from '../api/client'
import { useProductionMedia } from './useProductionMedia'
import {
  getProductionMediaJson,
  getProductionMediaText,
  isTerminal,
  mediaFileKey,
  mediaUrl,
  type MediaQcCheck,
  type MediaQcReport,
  type MediaRole,
  type ProductionMediaAsset,
  type ProductionRequestDetail,
} from '../api/production'
import { buttonClass } from '../ui/buttonStyles'
import { Card } from '../ui/Card'
import { DataTable } from '../ui/DataTable'
import { EmptyState, ErrorState, InlineNotice, LoadingSkeleton } from '../ui/States'
import { StatusBadge } from '../ui/StatusBadge'

const TITLE = '영상·미디어'

const ROLE: Record<MediaRole, string> = {
  NORMALIZED_IMAGE: '장면 이미지',
  PCM_AUDIO: '장면 낭독',
  SRT: '자막(SRT)',
  MP4: '최종 영상',
  QC_REPORT: 'QC 보고서',
  MANIFEST: 'manifest',
}

const CHECK: Record<string, string> = {
  STREAMS: '스트림 구성',
  VIDEO_CODEC: '영상 코덱(H.264 High)',
  RESOLUTION: '해상도 1080×1920',
  PIXEL_FORMAT: '픽셀 형식 yuv420p',
  COLOR: '색 범위·BT.709',
  FRAME_RATE: '30 FPS 고정',
  AUDIO_CODEC: '음성 코덱(AAC-LC)',
  AUDIO_FORMAT: '48 kHz 스테레오',
  CONTAINER: 'MP4 컨테이너',
  FASTSTART: 'faststart',
  RENDER_PLAN_BINDING: '렌더 계획 일치',
  FRAME_COUNT: '디코딩 프레임 수',
  CFR_PACKETS: '프레임 시간(CFR)',
  AV_LENGTH: '영상·음성 길이 차',
  FULL_DECODE: '전체 디코딩',
  AUDIO_SAMPLES: '디코딩 샘플 수',
  CLIPPING: '클리핑',
  LOUDNESS: '통합 음량',
  TRUE_PEAK: 'True Peak',
  NARRATION_DURATION: '실제 낭독 길이',
  VIDEO_COVERS_NARRATION: '마지막 낭독 포함',
  SYNC_EVIDENCE: '싱크 근거',
  INTERNAL_SILENCE: '장면 내부 무음',
  SUBTITLE_CUE_COUNT: '자막 cue 수',
  SUBTITLE_TEXT_COVERAGE: '자막 원문 일치',
  SUBTITLE_TIMING: '자막 시간',
  SUBTITLE_ORDER: '자막 순서·겹침',
  ASS_PARITY: 'ASS·SRT 일치',
  SUBTITLE_SAFE_AREA: '자막 안전 영역(실제 글리프)',
  SCENE_SAMPLE_FRAMES: '장면 표본 프레임',
  METADATA_PLAN: '메타데이터 일치',
  RIGHTS_COMPLETE: '자산 권리',
}

const RESULT = {
  PASS: { text: '통과', tone: 'success' },
  WARN: { text: '경고', tone: 'warning' },
  BLOCK: { text: '차단', tone: 'danger' },
} as const

/**
 * 제작 상세의 영상·미디어(V2-04, FAKE/로컬). 확정된 장면 이미지·낭독·자막과, 기술 QC를 통과한 최종 MP4·QC 보고서·manifest만
 * 보여준다. 영상은 같은 origin 세션으로 스트리밍해 브라우저가 Range로 재생 위치를 옮긴다. 편집 기능은 없다.
 */
export function MediaSection({ request }: { request: ProductionRequestDetail }) {
  const media = useProductionMedia(request)
  if (media.isPending) {
    return (
      <Card title={TITLE}>
        <LoadingSkeleton rows={3} />
      </Card>
    )
  }
  if (media.isError) {
    if (media.error instanceof ApiError && media.error.status === 404) {
      return (
        <Card title={TITLE}>
          <EmptyState icon={<Film className="size-6" />} title="미디어 정보를 찾을 수 없습니다" description="요청이 삭제되었거나 볼 수 있는 권한이 없습니다." />
        </Card>
      )
    }
    return (
      <Card title={TITLE}>
        <ErrorState error={media.error} onRetry={() => void media.refetch()} />
      </Card>
    )
  }
  const assets = media.data.assets
  const of = (role: MediaRole) => assets.filter((asset) => asset.role === role)
  const video = of('MP4')[0]
  const srt = of('SRT')[0]
  const report = of('QC_REPORT')[0]
  const manifest = of('MANIFEST')[0]
  const images = of('NORMALIZED_IMAGE')
  const narration = of('PCM_AUDIO')
  const qcStatus = media.data.qc.status
  if (assets.length === 0) {
    return (
      <Card title={TITLE}>
        <EmptyState icon={<Film className="size-6" />} {...emptyCopy(request, qcStatus)} />
      </Card>
    )
  }
  return (
    <section aria-label={TITLE} className="grid grid-cols-1 gap-5">
      <InlineNotice tone="warning">
        FAKE·로컬 제작: 외부 AI 공급자를 호출하지 않고 권리를 확인한 로컬 fixture로 만든 파일입니다. 기술 QC 통과는 재생 가능성과
        기술 계약 충족만 뜻하며 콘텐츠 품질·사실성·게시 적합성 검토를 대신하지 않습니다.
      </InlineNotice>
      <Card
        title="최종 영상"
        description={video ? '기술 QC를 통과한 1080×1920 30 FPS MP4입니다.' : undefined}
        actions={
          video && (
            <>
              <a className={buttonClass('primary', 'sm')} href={mediaUrl(request.id, video.id, true)} download>
                <Download aria-hidden className="size-4" />
                영상 다운로드
              </a>
              {srt && (
                <a className={buttonClass('secondary', 'sm')} href={mediaUrl(request.id, srt.id, true)} download>
                  <FileText aria-hidden className="size-4" />
                  자막 다운로드
                </a>
              )}
            </>
          )
        }
      >
        {video ? (
          <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
            <video
              aria-label="최종 영상 미리보기"
              className="aspect-[9/16] w-full max-w-[22rem] rounded-lg border border-line bg-black"
              controls
              playsInline
              preload="metadata"
              src={mediaUrl(request.id, video.id)}
            />
            <div className="grid min-w-0 grid-cols-1 content-start gap-4">
              {report ? <QcSummary requestId={request.id} report={report} /> : null}
              <FileList requestId={request.id} assets={[video, srt, report, manifest].filter(isAsset)} />
            </div>
          </div>
        ) : (
          <EmptyState
            icon={<Film className="size-6" />}
            title={qcStatus === 'FAILED' ? '기술 QC를 통과하지 못해 최종 영상을 제공하지 않습니다' : '최종 영상은 기술 QC 통과 후 표시됩니다'}
            description={qcStatus === 'FAILED'
              ? '진행 단계의 마지막 오류에서 차단 항목을 확인하세요. 통과하지 못한 파일은 재생·다운로드할 수 없습니다.'
              : '렌더링과 전체 디코딩 검사가 끝나면 이곳에서 재생하고 내려받을 수 있습니다.'}
          />
        )}
      </Card>
      {(images.length > 0 || narration.length > 0) && (
        <Card title="장면 이미지·낭독" description={`장면 ${Math.max(images.length, narration.length)}개 · 정규화된 1080×1920 이미지와 48 kHz 낭독`}>
          <ol aria-label="장면 미디어" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
            {Array.from({ length: Math.max(images.length, narration.length) }, (_, index) => (
              <li key={index} className="grid min-w-0 content-start gap-2">
                <p className="text-sm font-medium">장면 {index + 1}</p>
                {images[index] ? (
                  <img
                    alt={`장면 ${index + 1} 이미지`}
                    className="aspect-[9/16] w-full rounded-md border border-line bg-subtle object-cover"
                    loading="lazy"
                    src={mediaUrl(request.id, images[index].id)}
                  />
                ) : (
                  <div className="flex aspect-[9/16] items-center justify-center rounded-md border border-dashed border-line-strong text-muted">
                    <ImageIcon aria-hidden className="size-5" />
                    <span className="sr-only">이미지 없음</span>
                  </div>
                )}
                {narration[index] && (
                  <audio aria-label={`장면 ${index + 1} 낭독`} className="w-full" controls preload="none"
                    src={mediaUrl(request.id, narration[index].id)} />
                )}
              </li>
            ))}
          </ol>
        </Card>
      )}
      {srt && <SubtitleCard requestId={request.id} srt={srt} />}
    </section>
  )
}

function isAsset(asset: ProductionMediaAsset | undefined): asset is ProductionMediaAsset {
  return asset !== undefined
}

function emptyCopy(request: ProductionRequestDetail, qcStatus: string) {
  if (!isTerminal(request.status)) {
    return { title: '아직 확정된 미디어가 없습니다', description: '장면 이미지와 낭독이 확정되면 순서대로 표시됩니다.' }
  }
  if (qcStatus === 'FAILED' || request.status === 'FAILED') {
    return { title: '확정된 미디어가 없습니다', description: '미디어 단계 전에 중단되었거나 공개할 수 있는 파일이 만들어지지 않았습니다.' }
  }
  return {
    title: '이 요청은 영상 파일을 만들지 않았습니다',
    description: '미디어 제작을 선택하지 않은 요청이거나 이전 방식으로 만든 요청은 단계와 비용 정산만 검증합니다.',
  }
}

function size(bytes: string): string {
  const value = Number(bytes)
  if (!Number.isFinite(value)) return `${bytes} B`
  if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${value} B`
}

function FileList({ requestId, assets }: { requestId: string; assets: ProductionMediaAsset[] }) {
  return (
    <div className="min-w-0">
      <h3 className="text-sm font-medium text-muted">파일</h3>
      <ul aria-label="최종 파일" className="mt-1.5 grid grid-cols-1 gap-1.5">
        {assets.map((asset) => (
          <li key={asset.id} className="flex min-w-0 flex-wrap items-center justify-between gap-2 rounded-md border border-line px-3 py-2 text-sm">
            <span className="min-w-0 flex-1 basis-48">
              <span className="font-medium">{ROLE[asset.role]}</span>
              <span className="num ml-2 text-muted">{size(asset.byteLength)}</span>
              <span className="num block truncate text-xs text-muted" title={asset.sha256}>SHA-256 {asset.sha256}</span>
            </span>
            <a className={buttonClass('ghost', 'sm')} href={mediaUrl(requestId, asset.id, true)} download>
              <Download aria-hidden className="size-4" />
              <span className="sr-only">{ROLE[asset.role]}</span> 내려받기
            </a>
          </li>
        ))}
      </ul>
    </div>
  )
}

function QcSummary({ requestId, report }: { requestId: string; report: ProductionMediaAsset }) {
  const qc = useQuery({
    queryKey: mediaFileKey(requestId, report.id),
    queryFn: () => getProductionMediaJson<MediaQcReport>(requestId, report.id),
    staleTime: Infinity,
  })
  if (qc.isPending) return <LoadingSkeleton rows={2} />
  if (qc.isError) return <ErrorState error={qc.error} compact onRetry={() => void qc.refetch()} />
  const data = qc.data
  const warnings = data.checks.filter((check) => check.result === 'WARN').length
  return (
    <div className="grid min-w-0 grid-cols-1 gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge tone={warnings > 0 ? 'warning' : 'success'}>
          {warnings > 0 ? `기술 QC 통과 · 경고 ${warnings}건` : '기술 QC 통과'}
        </StatusBadge>
        <span className="num text-sm text-muted">
          {data.durationSeconds.toFixed(2)}초 · {data.frames}프레임 · 낭독 {data.narrationSeconds.toFixed(2)}초
        </span>
      </div>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:grid-cols-3">
        <div><dt className="text-muted">통합 음량</dt><dd className="num">{data.integratedLufs.toFixed(1)} LUFS</dd></div>
        <div><dt className="text-muted">True Peak</dt><dd className="num">{data.truePeakDbtp.toFixed(1)} dBTP</dd></div>
        <div><dt className="text-muted">정규화</dt><dd>{data.normalizationType ?? '—'}</dd></div>
      </dl>
      <details className="min-w-0 text-sm">
        <summary className="cursor-pointer font-medium">검사 항목 {data.checks.length}개</summary>
        <div className="mt-2 min-w-0">
          <DataTable<MediaQcCheck>
            caption="기술 QC 검사 항목"
            rows={data.checks}
            rowKey={(check) => check.code}
            rowTone={(check) => (check.result === 'BLOCK' ? 'danger' : check.result === 'WARN' ? 'warning' : undefined)}
            columns={[
              { key: 'code', header: '항목', cell: (check) => CHECK[check.code] ?? check.code },
              { key: 'result', header: '결과', cell: (check) => <StatusBadge tone={RESULT[check.result]?.tone ?? 'neutral'}>{RESULT[check.result]?.text ?? check.result}</StatusBadge> },
              { key: 'measured', header: '측정값', cell: (check) => <span className="num break-all text-sm">{check.measured}</span> },
              { key: 'expected', header: '기준', hideBelow: 'md', cell: (check) => <span className="text-sm text-muted">{check.expected}</span> },
            ]}
          />
        </div>
      </details>
    </div>
  )
}

function SubtitleCard({ requestId, srt }: { requestId: string; srt: ProductionMediaAsset }) {
  const text = useQuery({
    queryKey: mediaFileKey(requestId, srt.id),
    queryFn: () => getProductionMediaText(requestId, srt.id),
    staleTime: Infinity,
  })
  return (
    <Card title="자막" description="검토용 SRT입니다. 영상에는 같은 시간표의 ASS 자막이 번인되어 있습니다.">
      {text.isPending ? (
        <LoadingSkeleton rows={3} />
      ) : text.isError ? (
        <ErrorState error={text.error} onRetry={() => void text.refetch()} />
      ) : (
        <details>
          <summary className="cursor-pointer text-sm font-medium">자막 {cueCount(text.data)}개 보기</summary>
          <pre aria-label="SRT 자막" className="num mt-2 max-h-80 overflow-auto rounded-md border border-line bg-subtle p-3 text-sm whitespace-pre-wrap">
            {text.data}
          </pre>
        </details>
      )}
    </Card>
  )
}

function cueCount(srt: string): number {
  return srt.split(/\n\n+/).filter((block) => block.trim() !== '').length
}

import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { channels, csrf, error, health, me, mockApi, on, page, renderApp } from '../test/mockApi'

const allChannels = on('GET', '/api/v1/channels', 200, { ...page(channels), size: 100 })
const stages = ['DISCOVER', 'SELECT', 'RESEARCH', 'SCRIPT', 'SCENES', 'IMAGE', 'TTS', 'SUBTITLE', 'RENDER', 'METADATA', 'QC']
const BASE = '/api/v1/production-requests/42'

function detail(status: string, qc = status === 'SUCCEEDED' ? 'SUCCEEDED' : 'PENDING') {
  return {
    id: '42', channelId: '5', contentType: 'SHORTS', status, contentId: '8', runId: '9', cancelRequestedAt: null,
    limitKrw: '2000.000000', reservedKrw: '0', recognizedKrw: '16.000000',
    steps: stages.map((stage) => ({
      stage, targetKey: 'video', inputGeneration: 1, status: stage === 'QC' ? qc : status === 'SUCCEEDED' ? 'SUCCEEDED' : 'PENDING',
      attempts: 1, lastErrorCode: stage === 'QC' && qc === 'FAILED' ? 'QC_LOUDNESS' : null,
    })),
  }
}

const sha = (c: string) => c.repeat(64)
const asset = (id: string, stage: string, role: string, contentType: string, byteLength: string) =>
  ({ id, stage, role, contentType, byteLength, sha256: sha(id.slice(-1)) })
const sceneAssets = [
  asset('101', 'IMAGE', 'NORMALIZED_IMAGE', 'image/png', '2669639'),
  asset('102', 'IMAGE', 'NORMALIZED_IMAGE', 'image/png', '3628620'),
  asset('111', 'TTS', 'PCM_AUDIO', 'audio/wav', '236722'),
  asset('112', 'TTS', 'PCM_AUDIO', 'audio/wav', '865690'),
  asset('203', 'SUBTITLE', 'SRT', 'application/x-subrip', '1437'),
]
const finalAssets = [
  ...sceneAssets,
  asset('301', 'RENDER', 'MP4', 'video/mp4', '20732294'),
  asset('401', 'QC', 'QC_REPORT', 'application/json', '4688'),
  asset('402', 'QC', 'MANIFEST', 'application/json', '17603'),
]
const report = {
  status: 'PASS', checker: 'qc-1', frames: 1512, durationSeconds: 50.4, narrationSeconds: 50.3719375,
  integratedLufs: -15.8, truePeakDbtp: -1.9, normalizationType: 'linear',
  checks: [
    { code: 'RESOLUTION', target: 'MP4', result: 'PASS', measured: '1080x1920', expected: '1080x1920' },
    { code: 'SCENE_SAMPLE_FRAMES', target: 'MP4', result: 'WARN', measured: '1->2(0.41)', expected: 'own image' },
  ],
}
const srt = '1\n00:00:00,067 --> 00:00:02,167\nGuess what this\nsugar cube weighs?\n\n2\n00:00:02,533 --> 00:00:03,533\nIt is not sugar.\n\n'

function api(detailBody: unknown, media: unknown, extra: Parameters<typeof mockApi> = []) {
  const calls = mockApi(
    me, csrf, health, allChannels,
    on('GET', BASE, 200, detailBody),
    on('GET', `${BASE}/discovery`, 404, error('RESOURCE_NOT_FOUND', 'none')),
    on('GET', `${BASE}/planning`, 200, { executionMode: 'FAKE', results: [] }),
    on('GET', `${BASE}/media`, 200, media),
    on('GET', `${BASE}/media/401`, 200, report),
    ...extra,
  )
  const base = globalThis.fetch
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) =>
    String(input) === `${BASE}/media/203` ? new Response(srt, { status: 200, headers: { 'Content-Type': 'application/x-subrip' } })
      : base(input, init))
  return calls
}

describe('Production media preview (V2-04)', () => {
  it('plays the QC-passed video from the same-origin stream and offers server-named downloads', async () => {
    api(detail('SUCCEEDED'), { mode: 'FAKE', qc: { status: 'SUCCEEDED' }, assets: finalAssets })
    renderApp('/production/42')
    const section = await screen.findByRole('region', { name: '영상·미디어' })
    const video = within(section).getByLabelText('최종 영상 미리보기')
    expect(video).toHaveAttribute('src', `${BASE}/media/301`)
    expect(video).toHaveAttribute('controls')
    expect(within(section).getByRole('link', { name: '영상 다운로드' })).toHaveAttribute('href', `${BASE}/media/301?download=true`)
    expect(within(section).getByRole('link', { name: '자막 다운로드' })).toHaveAttribute('href', `${BASE}/media/203?download=true`)
    expect(await within(section).findByText('기술 QC 통과 · 경고 1건')).toBeInTheDocument()
    expect(within(section).getByText('-15.8 LUFS')).toBeInTheDocument()
    expect(within(section).getByText('-1.9 dBTP')).toBeInTheDocument()
    expect(within(section).getByText(/50\.40초 · 1512프레임 · 낭독 50\.37초/)).toBeInTheDocument()
    const files = within(section).getByRole('list', { name: '최종 파일' })
    expect(within(files).getAllByRole('listitem')).toHaveLength(4)
    expect(files).toHaveTextContent('19.8 MB')
    expect(within(section).getByText(/재생 가능성과\s+기술 계약 충족만 뜻하며/)).toBeInTheDocument()
    // The generic "no video file" FAKE notice is replaced once a final video exists.
    expect(screen.queryByText(/영상 파일 생성 없이/)).not.toBeInTheDocument()

    const scenes = within(section).getByRole('list', { name: '장면 미디어' })
    expect(within(scenes).getByRole('img', { name: '장면 2 이미지' })).toHaveAttribute('src', `${BASE}/media/102`)
    expect(within(scenes).getByLabelText('장면 1 낭독')).toHaveAttribute('src', `${BASE}/media/111`)

    const user = userEvent.setup()
    await user.click(await within(section).findByText('자막 2개 보기'))
    expect(within(section).getByLabelText('SRT 자막')).toHaveTextContent('Guess what this')
    await user.click(within(section).getByText('검사 항목 2개'))
    const checks = within(section).getByRole('table', { name: '기술 QC 검사 항목' })
    expect(checks).toHaveTextContent('해상도 1080×1920')
    expect(checks).toHaveTextContent('경고')
  })

  it('keeps the final video hidden until QC passes and explains a QC block', async () => {
    api(detail('FAILED', 'FAILED'), { mode: 'FAKE', qc: { status: 'FAILED' }, assets: sceneAssets })
    renderApp('/production/42')
    const section = await screen.findByRole('region', { name: '영상·미디어' })
    expect(within(section).getByText('기술 QC를 통과하지 못해 최종 영상을 제공하지 않습니다')).toBeInTheDocument()
    expect(within(section).queryByLabelText('최종 영상 미리보기')).not.toBeInTheDocument()
    expect(within(section).queryByRole('link', { name: '영상 다운로드' })).not.toBeInTheDocument()
    expect(within(section).getByRole('list', { name: '장면 미디어' })).toBeInTheDocument()
  })

  it('shows an empty state for requests without media and keeps the generic FAKE notice', async () => {
    api(detail('SUCCEEDED'), { mode: 'FAKE', qc: { status: 'SUCCEEDED' }, assets: [] })
    renderApp('/production/42')
    expect(await screen.findByText('이 요청은 영상 파일을 만들지 않았습니다')).toBeInTheDocument()
    expect(await screen.findByText(/영상 파일 생성 없이/)).toBeInTheDocument()
  })

  it('shows an error with retry when the media list fails', async () => {
    mockApi(
      me, csrf, health, allChannels,
      on('GET', BASE, 200, detail('RUNNING')),
      on('GET', `${BASE}/discovery`, 404, error('RESOURCE_NOT_FOUND', 'none')),
      on('GET', `${BASE}/planning`, 200, { executionMode: 'FAKE', results: [] }),
      on('GET', `${BASE}/media`, 500, error('INTERNAL_ERROR', '미디어 목록을 읽지 못했습니다.')),
    )
    renderApp('/production/42')
    const alert = await screen.findByText('미디어 목록을 읽지 못했습니다.', {}, { timeout: 4000 })
    expect(alert.closest('[role="alert"]')).not.toBeNull()
    expect(within(alert.closest('[role="alert"]') as HTMLElement).getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('sends the user to login when the session expires while loading media', async () => {
    let signedIn = true
    mockApi(
      csrf, health, allChannels,
      (call) => {
        if (call.url === '/api/v1/auth/me') {
          return signedIn ? { status: 200, body: { id: '1', login: 'admin', role: 'ADMIN', lastLoginAt: null } }
            : { status: 401, body: error('UNAUTHORIZED', 'signed out') }
        }
        if (call.url === `${BASE}/media`) {
          signedIn = false
          return { status: 401, body: error('UNAUTHORIZED', 'login required') }
        }
      },
      on('GET', BASE, 200, detail('RUNNING')),
      on('GET', `${BASE}/discovery`, 404, error('RESOURCE_NOT_FOUND', 'none')),
      on('GET', `${BASE}/planning`, 200, { executionMode: 'FAKE', results: [] }),
    )
    renderApp('/production/42')
    expect(await screen.findByText(/세션이 만료되었거나/, {}, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '영상·미디어' })).not.toBeInTheDocument()
  })
})

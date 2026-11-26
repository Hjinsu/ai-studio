import { readFileSync, existsSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { join } from 'node:path'
import { expect, test, type Page, type Route } from '@playwright/test'
import { installMockApi } from './mockServer'

/**
 * V2-04 media preview in a real browser (Edge): plays the produced MP4, seeks with HTTP Range requests and downloads
 * it with the server-generated name. The API is the e2e stand-in; file bytes are the real QC-passed acceptance MP4,
 * SRT and QC report (E2E_MEDIA_DIR, Git-external), served with the same single-range rules as the backend.
 */
const MEDIA = process.env.E2E_MEDIA_DIR
const SHOTS = process.env.E2E_SCREENSHOTS
const BASE = '/api/v1/production-requests/77'

const stages = ['DISCOVER', 'SELECT', 'RESEARCH', 'SCRIPT', 'SCENES', 'IMAGE', 'TTS', 'SUBTITLE', 'RENDER', 'METADATA', 'QC']
const detail = {
  id: '77', channelId: '1', contentType: 'SHORTS', status: 'SUCCEEDED', contentId: '8', runId: '9', cancelRequestedAt: null,
  limitKrw: '2000.000000', reservedKrw: '0', recognizedKrw: '10.000000',
  steps: stages.map((stage) => ({ stage, targetKey: 'video', inputGeneration: 1, status: 'SUCCEEDED', attempts: 1, lastErrorCode: null })),
}

function range(header: string | undefined, length: number): { status: number; start: number; end: number } {
  const match = header ? /^bytes=(\d*)-(\d*)$/.exec(header) : null
  if (!match || (match[1] === '' && match[2] === '')) return { status: 200, start: 0, end: length - 1 }
  if (match[1] === '') {
    const suffix = Math.min(Number(match[2]), length)
    return { status: 206, start: length - suffix, end: length - 1 }
  }
  const start = Number(match[1])
  if (start >= length) return { status: 416, start: 0, end: -1 }
  const end = match[2] === '' ? length - 1 : Math.min(Number(match[2]), length - 1)
  return { status: 206, start, end }
}

async function serve(route: Route, bytes: Buffer, type: string, name: string, served: string[]) {
  const url = new URL(route.request().url())
  const r = range(await route.request().headerValue('range') ?? undefined, bytes.length)
  served.push(`${r.status} ${route.request().headers().range ?? '-'}`)
  const headers: Record<string, string> = { 'Accept-Ranges': 'bytes', 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff', 'Content-Type': type }
  if (url.searchParams.get('download') === 'true') headers['Content-Disposition'] = `attachment; filename="${name}"`
  if (r.status === 416) return route.fulfill({ status: 416, headers: { ...headers, 'Content-Range': `bytes */${bytes.length}` }, body: '' })
  if (r.status === 206) headers['Content-Range'] = `bytes ${r.start}-${r.end}/${bytes.length}`
  return route.fulfill({ status: r.status, headers, body: bytes.subarray(r.start, r.end + 1) })
}

async function install(page: Page, topic: string) {
  await installMockApi(page, { signedIn: true })
  const dir = join(MEDIA as string, `it-${topic}`)
  const video = readFileSync(join(dir, `${topic}.mp4`))
  const srt = readFileSync(join(dir, `${topic}.srt`))
  const report = readFileSync(join(dir, 'qc-report.json'))
  const sha = (c: string) => c.repeat(64)
  const media = {
    mode: 'FAKE', qc: { status: 'SUCCEEDED' },
    assets: [
      { id: '203', stage: 'SUBTITLE', role: 'SRT', contentType: 'application/x-subrip', byteLength: String(srt.length), sha256: sha('3') },
      { id: '301', stage: 'RENDER', role: 'MP4', contentType: 'video/mp4', byteLength: String(video.length), sha256: sha('1') },
      { id: '401', stage: 'QC', role: 'QC_REPORT', contentType: 'application/json', byteLength: String(report.length), sha256: sha('4') },
    ],
  }
  const served: string[] = []
  const handler = async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    if (path === BASE) return route.fulfill({ json: detail })
    if (path === `${BASE}/discovery`) return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND', message: 'none', traceId: 't', timestamp: '2026-11-27T00:00:00Z', details: [] } })
    if (path === `${BASE}/planning`) return route.fulfill({ json: { executionMode: 'FAKE', results: [] } })
    if (path === `${BASE}/media`) return route.fulfill({ json: media })
    if (path === `${BASE}/media/301`) return serve(route, video, 'video/mp4', 'asset-301.mp4', served)
    if (path === `${BASE}/media/203`) return serve(route, srt, 'application/x-subrip', 'asset-203.srt', served)
    if (path === `${BASE}/media/401`) return route.fulfill({ status: 200, contentType: 'application/json', body: report })
    return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND', message: 'none', traceId: 't', timestamp: '2026-11-27T00:00:00Z', details: [] } })
  }
  // Page routes take precedence over the generic API stand-in; download navigations are routed at context level.
  await page.route((url) => url.pathname.startsWith(BASE), handler)
  await page.context().route((url) => url.pathname.startsWith(BASE), handler)
  return { served, video }
}

// Browser downloads bypass request interception and reach the Vite proxy target; answer them like the backend does.
let files: Server | undefined
let current: Buffer | undefined
test.beforeAll(async () => {
  if (!MEDIA) return
  files = createServer((request, response) => {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1')
    if (url.pathname !== `${BASE}/media/301` || !current) {
      response.writeHead(404).end()
      return
    }
    const r = range(request.headers.range, current.length)
    const headers: Record<string, string> = { 'Accept-Ranges': 'bytes', 'Content-Type': 'video/mp4', 'Cache-Control': 'private, no-store' }
    if (url.searchParams.get('download') === 'true') headers['Content-Disposition'] = 'attachment; filename="asset-301.mp4"'
    if (r.status === 206) headers['Content-Range'] = `bytes ${r.start}-${r.end}/${current.length}`
    response.writeHead(r.status, headers).end(current.subarray(r.start, r.end + 1))
  })
  await new Promise<void>((resolve) => files?.listen(8080, '127.0.0.1', resolve))
})
test.afterAll(async () => {
  await new Promise<void>((resolve) => (files ? files.close(() => resolve()) : resolve()))
})

for (const topic of ['neutron-star', 'venus-day']) {
  test(`plays, seeks and downloads the ${topic} acceptance MP4 (V2-04)`, async ({ page }) => {
    test.skip(!MEDIA || !existsSync(join(MEDIA, `it-${topic}`)), 'E2E_MEDIA_DIR with acceptance media is not configured')
    const { served, video } = await install(page, topic)
    current = video
    await page.goto('/production/77')
    const section = page.getByRole('region', { name: '영상·미디어' })
    await expect(section.getByText('기술 QC 통과', { exact: true })).toBeVisible()
    const player = section.getByLabel('최종 영상 미리보기')
    const state = await player.evaluate(async (element) => {
      const v = element as HTMLVideoElement
      v.muted = true
      if (v.readyState < 1) await new Promise((resolve, reject) => {
        v.addEventListener('loadedmetadata', resolve, { once: true })
        v.addEventListener('error', () => reject(new Error(`media error ${v.error?.code}`)), { once: true })
      })
      const duration = v.duration
      await v.play()
      await new Promise((resolve) => setTimeout(resolve, 1200))
      const played = v.currentTime
      v.currentTime = 30
      await new Promise((resolve) => v.addEventListener('seeked', resolve, { once: true }))
      await new Promise((resolve) => setTimeout(resolve, 800))
      const afterSeek = v.currentTime
      v.pause()
      return { duration, played, afterSeek, width: v.videoWidth, height: v.videoHeight, readyState: v.readyState, error: v.error?.code ?? null }
    })
    expect(state.error).toBeNull()
    expect(state.width).toBe(1080)
    expect(state.height).toBe(1920)
    expect(state.duration).toBeGreaterThan(40)
    expect(state.duration).toBeLessThan(60)
    expect(state.played).toBeGreaterThan(0.3)
    expect(state.afterSeek).toBeGreaterThanOrEqual(30)
    expect(state.afterSeek).toBeLessThan(32)
    expect(state.readyState).toBeGreaterThanOrEqual(2)
    expect(served.some((entry) => entry.startsWith('206 bytes='))).toBe(true)
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/v2-04-${topic}-light.png`, fullPage: true })

    const download = page.waitForEvent('download')
    await section.getByRole('link', { name: '영상 다운로드' }).click()
    const file = await download
    expect(file.suggestedFilename()).toBe('asset-301.mp4')
    const saved = await file.path()
    expect(readFileSync(saved).equals(video)).toBe(true)

    await section.getByText(/자막 \d+개 보기/).click()
    await expect(section.getByLabel('SRT 자막')).toContainText('-->')
  })
}

test('media preview fits mobile and dark themes without horizontal overflow (V2-04)', async ({ page }) => {
  test.skip(!MEDIA || !existsSync(join(MEDIA, 'it-venus-day')), 'E2E_MEDIA_DIR with acceptance media is not configured')
  await page.addInitScript(() => window.localStorage.setItem('ai-studio.theme', 'dark'))
  await page.setViewportSize({ width: 390, height: 844 })
  await install(page, 'venus-day')
  await page.goto('/production/77')
  const section = page.getByRole('region', { name: '영상·미디어' })
  await expect(section.getByText('기술 QC 통과', { exact: true })).toBeVisible()
  await expect(page.locator('html')).toHaveClass(/dark/)
  const wide = await page.evaluate(() => Array.from(document.querySelectorAll('body *')).filter((e) => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1 && !(e.parentElement?.closest('.overflow-x-auto, .overflow-auto'))).slice(0, 12).map((e) => e.tagName + '.' + String(e.className).slice(0, 80) + ' ' + Math.round(e.getBoundingClientRect().right)))
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow, wide.join(' | ')).toBeLessThanOrEqual(1)
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/v2-04-venus-day-dark-mobile.png`, fullPage: true })
})

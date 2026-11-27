import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'

/**
 * V2-04 real-app acceptance (not part of `npm test`). Drives the actual bootJar backend through the Vite proxy in Edge:
 * browser login, API setup with the browser session (channel, budget, spending), a media-enabled MANUAL SHORTS request,
 * topic selection in the UI, all 11 stages on the protected FFmpeg runner, then playback, seek and the MP4/SRT
 * downloads from the detail page in light, dark and mobile layouts.
 *
 * Env: LIVE_URL (Vite origin), LIVE_LOGIN, LIVE_PASSWORD_FILE, LIVE_OUT (Git-external evidence dir).
 */
const expect = playwrightExpect.configure({ timeout: 20_000 })
const origin = process.env.LIVE_URL
const out = process.env.LIVE_OUT
const password = readFileSync(process.env.LIVE_PASSWORD_FILE, 'utf8').trim()
// LIVE_TOPICS limits the run (comma list); LIVE_REUSE=slug:requestId verifies an already SUCCEEDED request of this
// app instance (selected through the UI earlier) instead of producing a new one.
const ALL_TOPICS = [
  { slug: 'neutron-star', question: /teaspoon of neutron star/i },
  { slug: 'venus-day', question: /day on Venus longer than its year/i },
]
const only = (process.env.LIVE_TOPICS ?? '').split(',').filter(Boolean)
const TOPICS = ALL_TOPICS.filter((t) => only.length === 0 || only.includes(t.slug))
const REUSE = Object.fromEntries((process.env.LIVE_REUSE ?? '').split(',').filter(Boolean).map((e) => e.split(':')))
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex')
const log = (...args) => console.log(new Date().toISOString(), ...args)

const browser = await chromium.launch({ channel: 'msedge' })
const context = await browser.newContext({ acceptDownloads: true, locale: 'ko-KR', timezoneId: 'Asia/Seoul' })
const page = await context.newPage()
const results = []

async function api(method, path, body, headers = {}) {
  const csrf = await (await page.request.get(origin + '/api/v1/auth/csrf')).json()
  const response = await page.request.fetch(origin + path, {
    method, data: body, headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/json', ...headers },
  })
  const text = await response.text()
  if (!response.ok()) throw new Error(`${method} ${path} -> ${response.status()} ${text}`)
  return text ? JSON.parse(text) : null
}
const get = async (path) => {
  const response = await page.request.get(origin + path)
  if (!response.ok()) throw new Error(`GET ${path} -> ${response.status()}`)
  return response.json()
}

try {
  // 1. Real login through the UI.
  await page.goto(origin)
  await page.getByLabel('아이디').fill(process.env.LIVE_LOGIN ?? 'admin')
  await page.getByLabel('비밀번호', { exact: true }).fill(password)
  const login = page.waitForResponse((r) => r.url().endsWith('/api/v1/auth/login'))
  await page.getByRole('button', { name: '로그인' }).click()
  if (!(await login).ok()) throw new Error('Login failed')
  await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible()
  log('login ok')

  // 2. Setup with the same session: English SHORTS channel, budget policies (mock KRW), spending allowed.
  const channel = await api('POST', '/api/v1/channels', {
    name: `ifnova-v204-live-${Date.now()}`, language: 'en', timezone: 'UTC',
    initialConfig: {
      defaultContentType: 'SHORTS', shortsMinSeconds: 40, shortsMaxSeconds: 60, longformMinSeconds: 480,
      longformMaxSeconds: 720, syntheticDisclosureDefault: true,
      contentStrategy: 'English science Shorts that explain one surprising question with visuals',
      targetAudience: 'Curious teens and adults who like space, biology and physics',
      narrationStyle: 'Deadpan and dry', visualStyle: 'Cinematic comedy',
    },
  })
  const channelId = channel.id
  for (const periodType of ['DAY', 'MONTH']) {
    try {
      await api('POST', '/api/v1/budget-policies', { scope: 'SYSTEM', periodType, limitKrw: '1000', timezone: 'UTC' })
    } catch (e) {
      if (!String(e).includes('DUPLICATE_RESOURCE')) throw e
    }
  }
  await api('POST', '/api/v1/budget-policies',
    { scope: 'CONTENT_TYPE', channelId, contentType: 'SHORTS', periodType: 'LIFETIME', limitKrw: '100', timezone: 'UTC' })
  const control = await get('/api/v1/budget/control')
  if (!control.spendingEnabled) {
    await api('POST', '/api/v1/budget/control/spending',
      { enabled: true, reason: 'V2-04 isolated real-app acceptance (FAKE mock KRW)', version: control.version })
  }
  log('setup ok channel', channelId)

  for (const topic of TOPICS) {
    // 3. Media-enabled MANUAL request; FAKE discovery is seeded per request, so retry until the topic is offered.
    let id = REUSE[topic.slug]
    let discovery
    if (id) log(topic.slug, 'reusing request', id)
    for (let attempt = 1; attempt <= 12 && !id; attempt++) {
      const created = await api('POST', '/api/v1/production-requests',
        { channelId, contentType: 'SHORTS', selectionMode: 'MANUAL', candidateCount: 10, mediaEnabled: true },
        { 'Idempotency-Key': `v204-live-${topic.slug}-${Date.now()}-${attempt}` })
      const deadline = Date.now() + 60_000
      while (Date.now() < deadline) {
        try {
          discovery = await get(`/api/v1/production-requests/${created.id}/discovery`)
          if (discovery.awaitingSelection) break
        } catch { /* discovery not ready yet */ }
        await page.waitForTimeout(1000)
      }
      if (!discovery?.awaitingSelection) throw new Error(`Discovery did not await selection for ${created.id}`)
      const offered = discovery.candidates.some((c) => topic.question.test(c.candidate?.centralQuestion ?? ''))
      log(topic.slug, 'request', created.id, 'offered', offered)
      if (offered) id = created.id
      else await api('POST', `/api/v1/production-requests/${created.id}/cancel`)
    }
    if (!id) throw new Error(`Topic ${topic.slug} was never offered`)

    // 4. Select the topic in the UI.
    if (!REUSE[topic.slug]) {
      await page.goto(`${origin}/production/${id}`)
      const candidates = page.getByRole('list', { name: '소재 후보 목록' })
      const item = candidates.getByRole('listitem').filter({ hasText: topic.question })
      await item.getByRole('button', { name: '이 소재 선택' }).click()
      await page.getByRole('alertdialog', { name: '이 소재로 진행할까요?' }).getByRole('button', { name: '이 소재 선택' }).click()
      log(topic.slug, 'selected in UI')
    }

    // 5. Wait for all fixed stages (protected runner renders on S:).
    let detail
    const started = Date.now()
    for (;;) {
      detail = await get(`/api/v1/production-requests/${id}`)
      if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'RECOVERY_REQUIRED'].includes(detail.status)) break
      if (Date.now() - started > 25 * 60_000) throw new Error(`Timed out: ${JSON.stringify(detail.steps)}`)
      await page.waitForTimeout(3000)
    }
    log(topic.slug, 'status', detail.status, Math.round((Date.now() - started) / 1000) + 's')
    if (detail.status !== 'SUCCEEDED') throw new Error(`${topic.slug}: ${JSON.stringify(detail.steps)}`)
    const media = await get(`/api/v1/production-requests/${id}/media`)
    const dir = join(out, topic.slug)
    mkdirSync(dir, { recursive: true })
    writeFileSync(join(dir, 'request-detail.json'), JSON.stringify(detail, null, 2))
    writeFileSync(join(dir, 'media.json'), JSON.stringify(media, null, 2))
    for (const asset of media.assets) {
      if (['QC_REPORT', 'MANIFEST', 'METADATA'].includes(asset.role) || asset.contentType === 'application/json') {
        const r = await page.request.get(`${origin}/api/v1/production-requests/${id}/media/${asset.id}`)
        writeFileSync(join(dir, `${asset.stage}-${asset.role}-${asset.id}.json`.toLowerCase()), await r.body())
      }
    }

    // 6. Browser playback and seek on the real detail page (light).
    await page.goto(`${origin}/production/${id}`)
    const section = page.getByRole('region', { name: '영상·미디어' })
    await expect(section.getByText('기술 QC 통과', { exact: true })).toBeVisible()
    const ranges = []
    const onResponse = (r) => { if (r.url().includes(`/production-requests/${id}/media/`)) ranges.push(r.status()) }
    page.on('response', onResponse)
    const state = await section.getByLabel('최종 영상 미리보기').evaluate(async (element) => {
      const v = element
      v.muted = true
      if (v.readyState < 1) await new Promise((resolve, reject) => {
        v.addEventListener('loadedmetadata', resolve, { once: true })
        v.addEventListener('error', () => reject(new Error(`media error ${v.error?.code}`)), { once: true })
      })
      const duration = v.duration
      let stalls = 0
      v.addEventListener('waiting', () => { stalls++ })
      await v.play()
      const until = Date.now() + 10_000
      while (v.currentTime < 1 && Date.now() < until) await new Promise((resolve) => setTimeout(resolve, 100))
      const played = v.currentTime
      v.currentTime = 30
      await new Promise((resolve) => v.addEventListener('seeked', resolve, { once: true }))
      await new Promise((resolve) => setTimeout(resolve, 1000))
      const afterSeek = v.currentTime
      v.pause()
      return { duration, played, afterSeek, stalls, width: v.videoWidth, height: v.videoHeight, readyState: v.readyState, error: v.error?.code ?? null }
    })
    page.off('response', onResponse)
    expect(state.error).toBeNull()
    expect(state.width).toBe(1080)
    expect(state.height).toBe(1920)
    expect(state.played).toBeGreaterThanOrEqual(1)
    expect(state.duration).toBeGreaterThan(40)
    expect(state.duration).toBeLessThan(60)
    expect(state.afterSeek).toBeGreaterThanOrEqual(30)
    expect(state.readyState).toBeGreaterThanOrEqual(2)
    expect(ranges).toContain(206)
    // Scene images (lazy) must decode in the browser; scene narration must play.
    const sceneList = section.getByRole('list', { name: '장면 미디어' })
    await sceneList.scrollIntoViewIfNeeded()
    const images = sceneList.getByRole('img')
    const sceneCount = await images.count()
    expect(sceneCount).toBeGreaterThan(0)
    for (let i = 0; i < sceneCount; i++) {
      await images.nth(i).scrollIntoViewIfNeeded()
      await expect.poll(() => images.nth(i).evaluate((img) => img.complete && img.naturalWidth)).toBe(1080)
    }
    const narration = await sceneList.locator('audio').first().evaluate(async (a) => {
      a.muted = true
      a.preload = 'auto'
      await a.play()
      const until = Date.now() + 8000
      while (a.currentTime < 0.5 && Date.now() < until) await new Promise((r) => setTimeout(r, 100))
      a.pause()
      return { currentTime: a.currentTime, duration: a.duration, error: a.error?.code ?? null }
    })
    expect(narration.error).toBeNull()
    expect(narration.currentTime).toBeGreaterThanOrEqual(0.5)
    await section.getByRole('heading', { name: '최종 영상' }).scrollIntoViewIfNeeded().catch(() => {})
    await page.screenshot({ path: join(dir, 'browser-light.png'), fullPage: true })

    // 7. Downloads from the page buttons.
    const video = page.waitForEvent('download')
    await section.getByRole('link', { name: '영상 다운로드' }).click()
    const videoFile = await video
    const mp4 = join(dir, `${topic.slug}.mp4`)
    await videoFile.saveAs(mp4)
    const subtitle = page.waitForEvent('download')
    await section.getByRole('link', { name: '자막 다운로드' }).click()
    const srtFile = await subtitle
    const srt = join(dir, `${topic.slug}.srt`)
    await srtFile.saveAs(srt)
    const mp4Bytes = readFileSync(mp4)
    const listed = media.assets.find((a) => a.role === 'MP4')
    expect(sha256(mp4Bytes)).toBe(listed.sha256)
    expect(readFileSync(srt, 'utf8')).toContain('-->')
    await section.getByText(/자막 \d+개 보기/).click()
    await expect(section.getByLabel('SRT 자막')).toContainText('-->')

    // 8. Dark + mobile layout without horizontal overflow.
    // Separate context: same session cookie, but the dark theme must not leak into the next topic's light check.
    const darkContext = await browser.newContext({ locale: 'ko-KR', timezoneId: 'Asia/Seoul', storageState: await context.storageState() })
    const mobile = await darkContext.newPage()
    await mobile.addInitScript(() => window.localStorage.setItem('ai-studio.theme', 'dark'))
    await mobile.setViewportSize({ width: 390, height: 844 })
    await mobile.goto(`${origin}/production/${id}`)
    await expect(mobile.getByRole('region', { name: '영상·미디어' }).getByText('기술 QC 통과', { exact: true })).toBeVisible()
    await expect(mobile.locator('html')).toHaveClass(/dark/)
    const overflow = await mobile.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow).toBeLessThanOrEqual(1)
    await mobile.screenshot({ path: join(dir, 'browser-dark-mobile.png'), fullPage: true })
    await darkContext.close()

    const result = {
      topic: topic.slug, requestId: id, status: detail.status,
      steps: detail.steps.map((s) => `${s.stage}:${s.status}:${s.attempts}`),
      mp4: { file: mp4, bytes: mp4Bytes.length, sha256: sha256(mp4Bytes), suggested: videoFile.suggestedFilename() },
      srt: { file: srt, sha256: sha256(readFileSync(srt)), suggested: srtFile.suggestedFilename() },
      playback: state, sceneImagesDecoded: sceneCount, sceneNarration: narration, mediaResponses: [...new Set(ranges)],
    }
    writeFileSync(join(dir, 'live-result.json'), JSON.stringify(result, null, 2))
    results.push(result)
    log(topic.slug, 'PASS', result.mp4.sha256)
  }
  writeFileSync(join(out, 'live-results.json'), JSON.stringify(results, null, 2))
  log('ALL PASS')
} finally {
  await browser.close()
}

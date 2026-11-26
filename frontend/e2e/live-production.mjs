import { chromium, expect as playwrightExpect } from '@playwright/test'

const expect = playwrightExpect.configure({ timeout: 20_000 })
const origin = process.env.REVIEW_BROWSER_URL
const browser = await chromium.launch({ channel: 'msedge' })
async function signIn(page, loginName) {
  await page.goto(origin)
  await page.getByLabel('아이디').fill(loginName)
  await page.getByLabel('비밀번호', { exact: true }).fill(process.env.REVIEW_BROWSER_PASSWORD)
  const login = page.waitForResponse((response) => response.url().endsWith('/api/v1/auth/login'))
  await page.getByRole('button', { name: '로그인' }).click()
  const loginResponse = await login
  if (!loginResponse.ok()) {
    const headers = await loginResponse.request().allHeaders()
    throw new Error(`Login HTTP ${loginResponse.status()}, session cookie: ${Boolean(headers.cookie)}, CSRF header: ${Boolean(headers['x-xsrf-token'])}`)
  }
  await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible()
}

try {
  const page = await browser.newPage()
  await signIn(page, 'admin')

  async function start(manual) {
    await page.goto(origin + '/production')
    await page.getByLabel('채널').selectOption(process.env.REVIEW_BROWSER_CHANNEL_ID)
    if (manual) await page.getByRole('radio', { name: /직접 선택/ }).check()
    await page.getByLabel('후보 수').selectOption('5')
    await page.getByRole('button', { name: '제작 시작…' }).click()
    await page.getByRole('alertdialog', { name: '제작을 시작할까요?' })
      .getByRole('button', { name: '제작 시작' }).click()
    await expect(page).toHaveURL(/\/production\/\d+$/)
    return page.url().split('/').at(-1)
  }

  const section = page.getByRole('region', { name: '자료 조사·대본·장면' })
  const scenes = section.getByRole('list', { name: '장면 목록' })
  const auto = await start(false)
  await expect(scenes.getByRole('listitem')).toHaveCount(5)
  await expect(section.getByText(/FAKE 실행 결과/)).toBeVisible()
  await expect(section.getByText('FIXTURE 시험 자료').first()).toBeVisible()
  await expect(section.getByText(/예상 발화/).first()).toBeVisible()
  await expect(scenes.getByText('이미지 프롬프트').first()).toBeVisible()
  await expect(scenes.getByText('내레이션').first()).toBeVisible()
  await expect(scenes.getByText('자막 원문').first()).toBeVisible()

  const manual = await start(true)
  const candidates = page.getByRole('list', { name: '소재 후보 목록' })
  const venus = candidates.getByRole('listitem').filter({ hasText: /Venus longer than its year/ })
  await venus.getByRole('button', { name: '이 소재 선택' }).click()
  await page.getByRole('alertdialog', { name: '이 소재로 진행할까요?' })
    .getByRole('button', { name: '이 소재 선택' }).click()
  await expect(scenes.getByRole('listitem')).toHaveCount(6)
  const review = section.locator('details').filter({ hasText: /개선 권고 [1-9]/ }).first()
  await review.locator('summary').click()
  await expect(review.getByText(/개선 권고 [1-9]/)).toBeVisible()

  const blocked = await start(false)
  await expect(section.getByText(/검수에서 필수 차단 항목/)).toBeVisible()
  await expect(scenes).toHaveCount(0)

  await page.goto(origin + '/production/requests')
  const history = page.getByRole('table', { name: '제작 요청 목록' })
  await expect(history.getByRole('link', { name: '#' + auto })).toBeVisible()
  await expect(history.getByRole('link', { name: '#' + blocked })).toBeVisible()
  await history.getByRole('link', { name: '#' + manual }).click()
  await expect(scenes).toBeVisible()
  await page.reload()
  await expect(scenes).toBeVisible()
  await page.getByRole('button', { name: '다크 모드' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(scenes).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
    .toBeLessThanOrEqual(1)

  const planningUrl = origin + '/api/v1/production-requests/' + manual + '/planning'
  expect((await page.request.get(planningUrl)).status()).toBe(200)
  const otherOwner = await browser.newPage()
  await signIn(otherOwner, 'other-reviewer')
  expect((await otherOwner.request.get(planningUrl)).status()).toBe(404)
  await otherOwner.close()

  await page.context().clearCookies()
  await page.goto(origin)
  await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
  const denied = await page.request.get(planningUrl)
  expect(denied.status()).toBe(401)
  console.log('Live Edge: AUTO, MANUAL, blocked, reload, history, dark/mobile, owner isolation and unauthenticated access passed.')
} finally {
  await browser.close()
}

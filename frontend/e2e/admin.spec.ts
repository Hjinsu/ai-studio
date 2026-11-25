import { expect, test, type Page } from '@playwright/test'
import { installMockApi } from './mockServer'

const SHOTS = process.env.E2E_SCREENSHOTS

async function shot(page: Page, name: string) {
  if (SHOTS) {
    await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
  }
}

/** 페이지가 가로로 넘치지 않는지(표는 자체 스크롤 영역 안에서만 넘칠 수 있음). */
async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow, `horizontal overflow on ${page.url()}`).toBeLessThanOrEqual(1)
}

async function signIn(page: Page) {
  await page.goto('/')
  await page.getByLabel('아이디').fill('admin')
  await page.getByLabel('비밀번호', { exact: true }).fill('correct-horse-battery-1')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible()
}

test('login with CSRF, dashboard, logout', async ({ page }) => {
  const { recorded } = await installMockApi(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
  await shot(page, '01-login-light')
  // 비밀번호 표시 전환
  await page.getByLabel('비밀번호', { exact: true }).fill('wrong-password')
  await page.getByRole('button', { name: '비밀번호 표시' }).click()
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveAttribute('type', 'text')
  await page.getByLabel('아이디').fill('admin')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByRole('alert')).toContainText('Invalid login or password.')
  await page.getByLabel('비밀번호', { exact: true }).fill('correct-horse-battery-1')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible()
  const login = recorded.filter((r) => r.path === '/api/v1/auth/login').at(-1)
  expect(login?.headers['x-xsrf-token']).toBe('tok-0')
  // 금액 표시: ₩ + 천 단위, 정밀도 유지
  await expect(page.getByText('₩300,000').first()).toBeVisible()
  await expect(page.getByText('₩126,450.5').first()).toBeVisible()
  await expectNoHorizontalOverflow(page)
  await shot(page, '02-dashboard-light')
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
})

test('dark mode applies everywhere and persists', async ({ page }) => {
  await installMockApi(page, { signedIn: true })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible()
  await page.getByRole('button', { name: '다크 모드' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  const bg = await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor)
  expect(bg).toBe('rgb(14, 17, 22)')
  await shot(page, '03-dashboard-dark')
  await page.reload()
  await expect(page.locator('html')).toHaveClass(/dark/)
  for (const [path, name] of [
    ['/channels', '04-channels-dark'],
    ['/budget/control', '05-control-dark'],
    ['/budget/reservations/41', '06-reservation-dark'],
  ]) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
    await shot(page, name)
  }
})

test('channel list, detail history diff and creation dialog', async ({ page }) => {
  const { recorded } = await installMockApi(page, { signedIn: true })
  await page.goto('/channels')
  await expect(page.getByRole('link', { name: 'IFNOVA' })).toBeVisible()
  const table = page.getByRole('table', { name: '채널 목록' })
  await expect(table.getByText('운영 중')).toBeVisible()
  await expect(table.getByText('일시정지')).toBeVisible()
  await shot(page, '07-channels-light')
  await page.getByRole('button', { name: '채널 등록' }).first().click()
  const dialog = page.getByRole('dialog', { name: '채널 등록' })
  await dialog.getByLabel('채널명').fill('새 채널')
  await shot(page, '08-channel-create')
  await dialog.getByRole('button', { name: '채널 등록' }).click()
  await expect(page.getByRole('heading', { name: '새 채널' })).toBeVisible()
  const created = recorded.find((r) => r.method === 'POST' && r.path === '/api/v1/channels')
  expect((created?.body as { initialConfig?: { shortsMinSeconds: number } }).initialConfig?.shortsMinSeconds).toBe(40)
  await page.goto('/channels/1?tab=history')
  await expect(page.getByRole('list', { name: '채널 설정 이력' }).getByText('롱폼 길이')).toBeVisible()
  await expect(page.getByRole('list', { name: '채널 설정 이력' }).getByText('8분–10분')).toBeVisible()
  await shot(page, '09-channel-history')
})

test('policy creation uses channel names and excludes archived channels', async ({ page }) => {
  const { recorded } = await installMockApi(page, { signedIn: true })
  await page.goto('/budget/policies')
  await expect(page.getByText('전면 차단').first()).toBeVisible()
  await shot(page, '10-policies')
  await page.getByRole('button', { name: '정책 만들기' }).first().click()
  const dialog = page.getByRole('dialog', { name: '예산 정책 만들기' })
  await dialog.getByText('채널별').click()
  const select = dialog.getByLabel('채널', { exact: true })
  await expect(select.locator('option')).toHaveText(['채널 선택', 'IFNOVA', 'Physics Lab (일시정지)'])
  await select.selectOption({ label: 'IFNOVA' })
  await dialog.getByLabel('한도').fill('50,000')
  await expect(dialog.getByText('₩50,000')).toBeVisible()
  await shot(page, '11-policy-create')
  await dialog.getByRole('button', { name: '정책 만들기' }).click()
  await expect.poll(() => recorded.find((r) => r.method === 'POST' && r.path === '/api/v1/budget-policies')?.body).toMatchObject({
    scope: 'CHANNEL', channelId: '1', limitKrw: '50000',
  })
})

test('spending allow needs confirmation with reason', async ({ page }) => {
  const { recorded } = await installMockApi(page, { signedIn: true })
  await page.goto('/budget/control')
  await expect(page.getByRole('heading', { name: '신규 지출 차단됨' })).toBeVisible()
  await page.getByRole('button', { name: '지출 허용…' }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog.getByRole('button', { name: '지출 허용' })).toBeDisabled()
  await shot(page, '12-spending-confirm')
  await dialog.getByRole('button', { name: '취소' }).click()
  expect(recorded.some((r) => r.path === '/api/v1/budget/control/spending')).toBe(false)
})

test('mobile layout: drawer navigation and no page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installMockApi(page, { signedIn: true })
  for (const path of ['/', '/channels', '/budget/policies', '/budget/usage', '/budget/reservations/41', '/cost-ledger', '/exchange-rates']) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
  }
  await page.goto('/')
  await page.getByRole('button', { name: '메뉴 열기' }).click()
  await shot(page, '13-mobile-drawer')
  await page.getByRole('dialog', { name: '주 메뉴' }).getByRole('link', { name: '비용 원장' }).click()
  await expect(page.getByRole('heading', { name: '비용 원장' })).toBeVisible()
  await shot(page, '14-mobile-ledger')
})

test('remaining screens render without errors', async ({ page }) => {
  await installMockApi(page, { signedIn: true })
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  for (const [path, heading, name] of [
    ['/budget/usage', '예산 사용량', '15-usage'],
    ['/budget/reservations', '예약·정산', '16-reservations'],
    ['/budget/reservations/41', '예약 #41', '17-reservation-detail'],
    ['/cost-ledger', '비용 원장', '18-ledger'],
    ['/exchange-rates', '환율 관리', '19-rates'],
    ['/system', '서버 상태', '20-health'],
    ['/budget/policies/1', '일간 예산 · 전체 채널', '21-policy-detail'],
  ]) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toContainText(heading)
    await page.waitForLoadState('networkidle')
    await shot(page, name)
  }
  expect(errors).toEqual([])
})

test('empty budget shows real empty state, not fake data', async ({ page }) => {
  await installMockApi(page, { signedIn: true, emptyBudget: true })
  await signInIfNeeded(page)
  await expect(page.getByText('시스템 예산 정책이 없습니다')).toBeVisible()
  await shot(page, '22-dashboard-empty')
})

async function signInIfNeeded(page: Page) {
  await page.goto('/')
  if (await page.getByRole('heading', { name: '관리자 로그인' }).isVisible().catch(() => false)) {
    await signIn(page)
  }
}

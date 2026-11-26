import { expect, test, type Page } from '@playwright/test'
import { installMockApi } from './mockServer'

const SHOTS = process.env.E2E_SCREENSHOTS

async function shot(page: Page, name: string) {
  if (SHOTS) {
    await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
  }
}

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow, `horizontal overflow on ${page.url()}`).toBeLessThanOrEqual(1)
}

test('start production with manual selection, review candidates and choose one (V2-02)', async ({ page }) => {
  const { recorded } = await installMockApi(page, { signedIn: true })
  await page.goto('/')
  await page.getByRole('link', { name: '제작 시작' }).first().click()
  await expect(page.getByRole('heading', { name: '제작 시작' })).toBeVisible()
  await page.getByLabel('채널').selectOption({ label: 'IFNOVA' })
  await page.getByRole('radio', { name: /직접 선택/ }).check()
  await page.getByLabel('후보 수').selectOption('5')
  await shot(page, 'v2-02-start-light')
  await page.getByRole('button', { name: '제작 시작…' }).click()
  const confirm = page.getByRole('alertdialog', { name: '제작을 시작할까요?' })
  await expect(confirm).toContainText('직접 선택 · 후보 5개')
  await confirm.getByRole('button', { name: '제작 시작' }).click()

  await expect(page).toHaveURL(/\/production\/42$/)
  await expect(page.getByRole('heading', { name: '제작 요청 #42' })).toBeVisible()
  const list = page.getByRole('list', { name: '소재 후보 목록' })
  await expect(list.getByRole('listitem')).toHaveCount(5)
  await expect(list.getByRole('listitem').first()).toContainText('A Teaspoon of Neutron Star')
  await expect(list.getByText('중복', { exact: true })).toBeVisible()
  await expect(list.getByRole('button', { name: '이 소재 선택' })).toHaveCount(3)
  await shot(page, 'v2-02-detail-light')

  const venus = list.getByRole('listitem').filter({ hasText: 'A Day on Venus Outlasts Its Year' })
  await venus.getByRole('button', { name: '이 소재 선택' }).click()
  await page.getByRole('alertdialog', { name: '이 소재로 진행할까요?' }).getByRole('button', { name: '이 소재 선택' }).click()
  await expect(page.getByText(/후보 3번 선택을 기록했습니다/)).toBeVisible()
  await expect(list.getByRole('button', { name: '이 소재 선택' })).toHaveCount(0)

  const posts = recorded.filter((r) => r.method === 'POST' && r.path.startsWith('/api/v1/production-requests'))
  expect(posts[0].body).toEqual({ channelId: '1', contentType: 'SHORTS', selectionMode: 'MANUAL', candidateCount: 5 })
  expect(posts[0].headers['idempotency-key']).toMatch(/^ui-/)
  expect(posts[1].body).toEqual({ candidateId: 3, version: 1, batchToken: '1'.repeat(64) })
})

test('production history lists requests, marks FAKE completion and opens the detail (V2-02)', async ({ page }) => {
  await installMockApi(page, { signedIn: true })
  await page.goto('/')
  await page.getByRole('link', { name: '제작 이력' }).first().click()
  await expect(page.getByRole('heading', { name: '제작 이력' })).toBeVisible()
  await expect(page.getByText(/실제 AI 호출과 영상 파일 생성 없이/)).toBeVisible()
  const table = page.getByRole('table', { name: '제작 요청 목록' })
  await expect(table.getByRole('row')).toHaveCount(3)
  const done = table.getByRole('row').filter({ hasText: '#41' })
  await expect(done).toContainText('A Teaspoon of Neutron Star')
  await expect(done).toContainText('FAKE 완료')
  await expect(table.getByRole('row').filter({ hasText: '#42' })).toContainText('미선정')
  await shot(page, 'v2-02-history-light')
  await table.getByRole('link', { name: '#42' }).click()
  await expect(page).toHaveURL(/\/production\/42$/)
  await expect(page.getByRole('heading', { name: '제작 요청 #42' })).toBeVisible()
  await expect(page.getByRole('table', { name: '진행 단계' }).getByRole('columnheader', { name: '마지막 오류' })).toBeVisible()
})

test('production screens in dark mode and on mobile have no page overflow', async ({ page }) => {
  await installMockApi(page, { signedIn: true })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible()
  await page.getByRole('button', { name: '다크 모드' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  for (const [path, name] of [['/production', 'v2-02-start-dark'], ['/production/requests', 'v2-02-history-dark'], ['/production/42', 'v2-02-detail-dark']]) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await shot(page, name)
  }
  await page.setViewportSize({ width: 390, height: 844 })
  for (const [path, name] of [['/production', 'v2-02-start-mobile'], ['/production/requests', 'v2-02-history-mobile'], ['/production/42', 'v2-02-detail-mobile']]) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await shot(page, name)
  }
  await page.getByRole('list', { name: '소재 후보 목록' }).getByText('설명 관점·구성안·근거 계획').first().click()
  await expectNoHorizontalOverflow(page)
})

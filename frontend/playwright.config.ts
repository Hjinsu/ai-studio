import { defineConfig } from '@playwright/test'

/**
 * 브라우저 E2E(실제 Chromium 계열 브라우저 렌더링). API는 브라우저 네트워크 계층에서 가로채는 대역(e2e/mockServer.ts)으로
 * 대체해 백엔드·DB 없이 실행한다. 브라우저 다운로드 없이 설치된 Edge를 쓴다(PLAYWRIGHT_CHANNEL로 변경 가능,
 * CI 등에서는 PLAYWRIGHT_CHANNEL=chromium + npx playwright install chromium).
 */
const port = Number(process.env.E2E_PORT ?? 5174)

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  outputDir: process.env.E2E_OUTPUT ?? 'test-results',
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    channel: process.env.PLAYWRIGHT_CHANNEL ?? 'msedge',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
  },
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
})

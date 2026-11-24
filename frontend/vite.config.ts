import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// 개발 서버는 백엔드로 프록시한다. 기본값은 로컬 bootRun 주소(127.0.0.1 바인딩, ADR-023).
const backendUrl = process.env.AI_STUDIO_BACKEND_URL ?? 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': backendUrl,
      '/actuator': backendUrl,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    unstubGlobals: true,
  },
})

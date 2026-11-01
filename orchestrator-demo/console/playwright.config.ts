import { defineConfig } from '@playwright/test'

/**
 * Console E2E suite. The Vite dev server is started automatically; every
 * backend call (/api/**, /actuator/**, SSE) is intercepted at the network
 * level by e2e/mocks.ts, so the functional closed loop runs deterministically
 * without a Spring Boot process or a real model behind it.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 7_000 },
  fullyParallel: true,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'pnpm dev --strictPort',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})

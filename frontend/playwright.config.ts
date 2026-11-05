import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:15173',
    channel: 'msedge',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'mobile-390', use: { ...devices['Desktop Chrome'], viewport: { width: 390, height: 844 } } }
  ],
  webServer: {
    command: 'node ./node_modules/vite/bin/vite.js --config vite.config.ts --host 127.0.0.1 --port 15173',
    url: 'http://127.0.0.1:15173',
    timeout: 60_000,
    reuseExistingServer: false,
    env: { VITE_STATIC_DEMO: 'false', VITE_FROZEN_SNAPSHOT: 'true' }
  },
  workers: process.env.CI ? 2 : 2
})

import { defineConfig } from '@playwright/test';

/**
 * 浏览器 E2E（真实 Chrome 经 Vite 代理打后端，暴露前后端联通问题）。
 * 前置：后端 8080 与前端 dev 5174 已在运行（不配 webServer，服务由外部管理）。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5174',
    channel: 'chrome',
    headless: true,
    viewport: { width: 1600, height: 900 },
    actionTimeout: 15_000,
  },
});

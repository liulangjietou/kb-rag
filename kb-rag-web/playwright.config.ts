import { defineConfig } from '@playwright/test';

// 专用端口与独立进程保证验收的是当前工作区，接口由测试显式接管。
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  fullyParallel: true,
  workers: 2,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:20106',
    viewport: { width: 1440, height: 1000 },
    locale: 'zh-CN',
    reducedMotion: 'reduce',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...(process.env.PLAYWRIGHT_CHROME ? { channel: 'chrome' } : {}),
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 20106 --strictPort',
    url: 'http://127.0.0.1:20106',
    reuseExistingServer: false,
  },
});

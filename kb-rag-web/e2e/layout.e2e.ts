import { test, expect } from './fixtures';

const routes = [
  '/login',
  '/register',
  '/change-password',
  '/no-access',
  '/home',
  '/kb',
  '/kb/kb_fixture',
  '/search',
  '/chat',
  '/apps',
  '/apps/app_fixture',
  '/memory',
  '/memory/mem_fixture',
  '/mcp',
  '/eval',
  '/users',
  '/users/registration-reviews',
  '/roles',
  '/settings/tenants',
  '/settings/operation-audits',
  '/settings',
];
for (const width of [1440, 880, 390]) {
  for (const route of routes) {
    test(`${route} · ${width}px 布局与运行检查`, async ({ page, unexpectedRequests }, testInfo) => {
      const pageErrors: string[] = [];
      page.on('pageerror', (error) => pageErrors.push(error.message));
      await page.setViewportSize({ width, height: 1000 });
      if (route === '/login' || route === '/register')
        await page.addInitScript(() => localStorage.removeItem('kb-rag-web:auth-token'));
      await page.goto(route);
      await expect(
        page.locator('.page-header h1, .atlas-home__heading h1, .auth-card h2').first(),
      ).toBeVisible();
      await expect.poll(() => page.locator('.ant-spin-spinning').count()).toBe(0);
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth - innerWidth),
        '页面不能横向溢出',
      ).toBeLessThanOrEqual(0);
      expect(pageErrors, '不得出现未处理的浏览器异常').toEqual([]);
      expect(unexpectedRequests).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath('page.png'), fullPage: true });
    });
  }
}

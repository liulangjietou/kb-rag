import { DARK_THEME_IDS, THEME_IDS } from './theme-fixture';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';

test('1280×720 注册验证和资料提交的主要动作保持首屏可见', async ({ page, unexpectedRequests }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.addInitScript(() => localStorage.removeItem('kb-rag-web:auth-token'));
  await page.route('**/api/v1/registrations/verify-email', async (route) => {
    expect(route.request().postDataJSON()).toEqual({ email: 'new-user@example.com', code: '123456' });
    await route.fulfill({ json: { code: 'OK', data: { registration_ticket: 'fixture-ticket', expires_in_seconds: 600 } } });
  });
  await page.goto('/register');
  await expect(page.getByRole('button', { name: '验证邮箱并继续' })).toBeInViewport({ ratio: 1 });
  await page.getByPlaceholder('name@company.com').fill('new-user@example.com');
  await page.getByPlaceholder('输入 6 位验证码').fill('123456');
  await page.getByRole('button', { name: '验证邮箱并继续' }).click();
  await expect(page.getByPlaceholder('你的姓名')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('register-details.png'), fullPage: true });
  await expect(page.getByRole('button', { name: '提交注册申请' })).toBeInViewport({ ratio: 1 });
  expect(unexpectedRequests).toEqual([]);
});

for (const theme of THEME_IDS) {
  for (const width of [390, 768, 1280, 1440, 1920]) {
    test(`注册两步 · ${theme} · ${width}px`, async ({ page, unexpectedRequests }, testInfo) => {
      await page.setViewportSize({ width, height: width >= 1280 ? 720 : 844 });
      await page.addInitScript((preset) => {
        localStorage.removeItem('kb-rag-web:auth-token');
        localStorage.setItem('kb-rag-web:theme-preset', preset);
      }, theme);
      await page.route('**/api/v1/registrations/verify-email', async (route) => {
        await route.fulfill({ json: { code: 'OK', data: { registration_ticket: 'fixture-ticket', expires_in_seconds: 600 } } });
      });
      await page.goto('/register');
      await expect(page.getByPlaceholder('name@company.com')).toBeVisible();
      if (width === 1280) {
        const firstStep = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
        expect(firstStep.violations.map(({ id, nodes }) => ({ id, nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })) }))).toEqual([]);
      }
      await page.getByPlaceholder('name@company.com').fill('new-user@example.com');
      await page.getByPlaceholder('输入 6 位验证码').fill('123456');
      await page.getByRole('button', { name: '验证邮箱并继续' }).click();
      await expect(page.getByPlaceholder('你的姓名')).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
      if (width >= 1280) {
        await expect(page.getByRole('button', { name: '提交注册申请' })).toBeInViewport({ ratio: 1 });
      }
      if (width === 1280 || (DARK_THEME_IDS.includes(theme) && width === 390)) {
        const secondStep = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
        expect(secondStep.violations.map(({ id, nodes }) => ({ id, nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })) }))).toEqual([]);
      }
      await page.getByRole('button', { name: '上一步' }).click();
      await expect(page.getByPlaceholder('name@company.com')).toHaveValue('new-user@example.com');
      expect(unexpectedRequests).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath('register.png'), fullPage: true });
    });
  }
}

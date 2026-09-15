import { DARK_THEME_IDS, THEME_IDS } from './theme-fixture';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';

test('1280×720 同时开启目录和单点登录时，账号、验证码与提交按钮都在首屏', async ({ page, api, unexpectedRequests }, testInfo) => {
  api['/auth/sso-available'] = { sso_available: true };
  api['/auth/sso/providers'] = { oidc: true, saml: true, cas: true };
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.addInitScript(() => localStorage.removeItem('kb-rag-web:auth-token'));
  await page.goto('/login');
  await expect(page.getByRole('tab', { name: '域账号', exact: true })).toBeVisible();
  await expect(page.locator('.login-captcha--ready')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('login-desktop.png'), fullPage: true });
  for (const control of [page.getByLabel('域用户名'), page.locator('.login-captcha__puzzle'), page.getByRole('button', { name: '进入工作台' })]) {
    await expect(control).toBeInViewport({ ratio: 1 });
  }
  expect(unexpectedRequests).toEqual([]);
});

for (const theme of THEME_IDS) {
  for (const width of [390, 768, 1280, 1440, 1920]) {
    test(`登录 · ${theme} · ${width}px，主题、触控与布局`, async ({ page, api, unexpectedRequests }, testInfo) => {
      api['/auth/sso-available'] = { sso_available: true };
      api['/auth/sso/providers'] = { oidc: true, saml: true, cas: true };
      await page.setViewportSize({ width, height: width >= 1280 ? 720 : 844 });
      await page.addInitScript((preset) => {
        localStorage.removeItem('kb-rag-web:auth-token');
        localStorage.setItem('kb-rag-web:theme-preset', preset);
      }, theme);
      const pageErrors: string[] = [];
      page.on('pageerror', (error) => pageErrors.push(error.message));
      await page.goto('/login');
      await expect(page.locator('.login-captcha--ready')).toBeVisible();
      await expect(page.getByRole('button', { name: 'CAS 单点登录' })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
      if (width >= 1280) {
        await expect(page.getByRole('button', { name: '进入工作台' })).toBeInViewport({ ratio: 1 });
      }
      if (width === 390) {
        for (const name of ['进入工作台', 'OIDC 单点登录', 'SAML 单点登录', 'CAS 单点登录']) {
          const box = await page.getByRole('button', { name }).boundingBox();
          expect(box?.height).toBeGreaterThanOrEqual(44);
        }
      }
      if (width === 1280 || (DARK_THEME_IDS.includes(theme) && width === 390)) {
        const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
        expect(result.violations.map(({ id, nodes }) => ({ id, nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })) }))).toEqual([]);
      }
      expect(pageErrors).toEqual([]);
      expect(unexpectedRequests).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath('login.png'), fullPage: true });
    });
  }
}

test('上次成功使用平台账号后，即使启用目录也保留平台方式', async ({ page, api, unexpectedRequests }) => {
  api['/auth/sso-available'] = { sso_available: true };
  await page.addInitScript(() => {
    localStorage.removeItem('kb-rag-web:auth-token');
    localStorage.setItem('kb-rag.login-method.v1', 'LOCAL');
  });
  await page.goto('/login');
  await expect(page.getByRole('tab', { name: '平台账号', exact: true })).toHaveAttribute('aria-selected', 'true');
  expect(unexpectedRequests).toEqual([]);
});

for (const probe of ['sso-available', 'sso/providers']) {
  test(`${probe} 晚返回时保留密码管理器的原生自动填充`, async ({ page, unexpectedRequests }) => {
    let release: (() => void) | undefined;
    const responseReady = new Promise<void>((resolve) => { release = resolve; });
    await page.route(`**/api/v1/auth/${probe}`, async (route) => {
      await responseReady;
      const data = probe === 'sso-available'
        ? { sso_available: true }
        : { oidc: true, saml: false, cas: false };
      await route.fulfill({ json: { code: 'OK', data } });
    });
    await page.addInitScript(() => localStorage.removeItem('kb-rag-web:auth-token'));
    await page.goto('/login');
    const username = page.getByPlaceholder('输入邮箱或平台用户名');
    await expect(username).toBeVisible();
    await username.evaluate((input: HTMLInputElement) => { input.value = 'autofilled-user'; });
    await page.getByPlaceholder('输入平台密码').evaluate((input: HTMLInputElement) => { input.value = 'browser-owned-secret'; });
    release?.();
    if (probe === 'sso-available') {
      await expect(page.getByRole('tab', { name: '域账号', exact: true })).toBeVisible();
    } else {
      await expect(page.getByRole('button', { name: 'OIDC 单点登录' })).toBeVisible();
    }
    await expect(username).toHaveValue('autofilled-user');
    await expect(page.getByPlaceholder('输入平台密码')).toHaveValue('browser-owned-secret');
    expect(unexpectedRequests).toEqual([]);
  });
}

import { test, expect } from './fixtures';

test('退出携带原会话凭证，回到登录页后不弹认证错误', async ({ page, unexpectedRequests }) => {
  await page.route('**/api/v1/auth/logout', async (route) => {
    const authenticated = route.request().headers().satoken === 'ui-test-fixture';
    await route.fulfill({
      status: authenticated ? 200 : 401,
      json: authenticated
        ? { code: 'OK', data: null }
        : { code: 'UNAUTHORIZED', message: 'missing bearer token', request_id: 'logout-regression' },
    });
  });
  await page.goto('/home');
  await page.getByRole('button', { name: '打开账号菜单' }).click();
  const logoutResponse = page.waitForResponse('**/api/v1/auth/logout');
  const loginProbe = page.waitForResponse('**/api/v1/auth/sso-available');
  await page.getByRole('menuitem', { name: '退出登录' }).click();

  const response = await logoutResponse;
  expect(response.request().method()).toBe('POST');
  expect(response.request().headers().satoken).toBe('ui-test-fixture');
  expect(response.status()).toBe(200);
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '登录知识工作台' })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('kb-rag-web:auth-token'))).toBeNull();
  expect((await loginProbe).request().headers().satoken).toBeUndefined();
  await expect(page.locator('.ant-message-error')).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

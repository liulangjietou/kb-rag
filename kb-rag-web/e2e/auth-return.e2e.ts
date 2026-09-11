import { test, expect } from './fixtures';
import { employeeFixture, employeeUrl } from './employee-workspace-fixture';

test.use({ grantedPermissions: ['app:use'] });

test('强制改密页刷新后仍保留原会话，完成改密读取原历史', async ({ page, api, unexpectedRequests }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const user = api['/auth/me'] as Record<string, unknown>;
  user.must_change_password = true;
  const state = await employeeFixture(page);
  const changes: unknown[] = [];
  await page.route('**/api/v1/auth/change-password', async (route) => {
    changes.push(route.request().postDataJSON());
    user.must_change_password = false;
    await route.fulfill({ json: { code: 'OK', data: null } });
  });
  await page.goto(`${employeeUrl}#latest`);
  await expect(page).toHaveURL(/\/change-password$/);
  await page.reload();
  await page.getByPlaceholder('输入当前密码').fill('fixture-password');
  await page.getByPlaceholder('输入新密码', { exact: true }).fill('fixture-new-password');
  await page.getByPlaceholder('再次输入新密码').fill('fixture-new-password');
  await page.getByRole('button', { name: '更新密码并进入' }).click();
  await expect(page).toHaveURL(`${employeeUrl}#latest`);
  await expect(page.getByRole('table')).toBeVisible();
  expect(changes).toEqual([{ old_password: 'fixture-password', new_password: 'fixture-new-password' }]);
  expect(state.submissions).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

test('员工会话过期退回登录时保留站内地址，隐藏旧正文', async ({ page, unexpectedRequests }) => {
  await employeeFixture(page);
  await page.route('**/api/v1/workspace/apps', (route) => route.fulfill({
    status: 401, json: { code: 'UNAUTHORIZED', message: '登录已过期' },
  }));
  await page.goto(`${employeeUrl}#latest`);
  await expect(page).toHaveURL(/\/login$/);
  const from = await page.evaluate(() => window.history.state?.usr?.from);
  expect(from).toMatchObject({ pathname: '/workspace', search: '?app=app_fixture&conversation=conv_fixture', hash: '#latest' });
  await expect(page.getByRole('table')).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

import { test, expect } from './fixtures';
import { employeeConversation, employeeFixture, employeeRun, employeeUrl } from './employee-workspace-fixture';

test.use({ grantedPermissions: ['app:use'], viewport: { width: 390, height: 844 } });
const conversationRoute = '**/api/v1/workspace/apps/app_fixture/conversations/conv_fixture';
const historyRoute = `${conversationRoute}/runs?*`;
const forbidden = { code: 'FORBIDDEN', message: 'permission required: app:use' };
const accessMessage = '当前已无权访问这段会话，回答与引用已隐藏。权限恢复后可重新读取。';

test('打开引用后撤权显示明确状态，清除正文，恢复权限后可继续阅读', async ({ page, unexpectedRequests }) => {
  const state = await employeeFixture(page);
  await page.goto(employeeUrl);
  await expect(page.getByRole('article')).toHaveCount(1);
  await page.locator('.conversation-scroll').evaluate(element => {
    element.scrollTop = 0;
    element.dispatchEvent(new Event('scroll'));
  });
  await page.getByRole('button', { name: '查看引用 1', exact: true }).click();
  const reader = page.getByRole('dialog');
  await expect(reader.getByRole('heading', { name: '本次引用片段' })).toBeVisible();
  await page.route(historyRoute, route => route.fulfill({ status: 403, json: forbidden }));
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByRole('article')).toHaveCount(0);
  await expect.soft(reader.getByText(accessMessage)).toBeVisible();
  await expect.soft(page.getByRole('heading', { name: '会话不可访问' })).toBeVisible();
  await expect.soft(page.getByRole('button', { name: '回到最新回答', exact: false })).toHaveCount(0);
  await expect.soft(reader.getByText(employeeRun.references[0].content)).toHaveCount(0);
  await reader.getByRole('button', { name: '关闭', exact: true }).click();
  await expect.soft(page.getByRole('button', { name: '打开回答依据' })).toBeDisabled();
  await expect.soft(page.getByRole('textbox', { name: '输入知识问题' })).toBeDisabled();
  await expect.soft(page.getByText(forbidden.message, { exact: true })).toHaveCount(0);
  await page.unroute(historyRoute);
  await page.getByRole('button', { name: '重新读取', exact: true }).click();
  await expect(page.getByRole('heading', { name: employeeConversation.title })).toBeVisible();
  await expect(page.getByRole('article')).toHaveCount(1);
  await expect(page.getByRole('button', { name: '打开回答依据' })).toBeEnabled();
  await expect(page.getByRole('textbox', { name: '输入知识问题' })).toBeEnabled();
  expect(state.submissions).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

test('首次读取不存在的会话不会停留在加载标题', async ({ page, unexpectedRequests }) => {
  await employeeFixture(page);
  await page.route(conversationRoute, route => route.fulfill({ status: 404,
    json: { code: 'NOT_FOUND', message: 'conversation not found' } }));
  await page.goto(employeeUrl);
  await expect.soft(page.getByRole('heading', { name: '会话不可访问' })).toBeVisible();
  await expect.soft(page.locator('.conversation-banner')).toHaveText(/这段会话已不存在或当前不可访问/);
  await expect(page.getByRole('article')).toHaveCount(0);
  await expect(page.getByRole('textbox', { name: '输入知识问题' })).toBeDisabled();
  expect(unexpectedRequests).toEqual([]);
});

test('普通读取失败保留重试入口，成功后退出失败状态', async ({ page, unexpectedRequests }) => {
  await employeeFixture(page);
  await page.route(conversationRoute, route => route.fulfill({ status: 503,
    json: { code: 'UNAVAILABLE', message: '会话暂时无法读取，请重试' } }));
  await page.goto(employeeUrl);
  await expect(page.getByText('会话暂时无法读取，请重试', { exact: true })).toBeVisible();
  await expect.soft(page.getByRole('heading', { name: '会话读取失败' })).toBeVisible();
  await expect.soft(page.getByRole('button', { name: '打开回答依据' })).toBeDisabled();
  await page.unroute(conversationRoute);
  await page.getByRole('button', { name: '重新读取', exact: true }).click();
  await expect(page.getByRole('heading', { name: employeeConversation.title })).toBeVisible();
  await expect(page.getByRole('article')).toHaveCount(1);
  expect(unexpectedRequests).toEqual([]);
});

test('重命名请求遇到撤权时关闭失效表单并显示统一权限提示', async ({ page, unexpectedRequests }) => {
  const state = await employeeFixture(page);
  await page.goto(employeeUrl);
  await page.getByRole('button', { name: '重命名会话' }).click();
  await page.getByRole('textbox', { name: '会话标题' }).fill('新标题');
  await page.route(conversationRoute, route => route.request().method() === 'PATCH'
    ? route.fulfill({ status: 403, json: forbidden }) : route.fallback());
  await page.getByRole('dialog').getByRole('button', { name: /^保\s*存$/ }).click();
  await expect.soft(page.getByRole('dialog')).toHaveCount(0);
  await expect.soft(page.locator('.conversation-banner')).toHaveText(new RegExp(accessMessage));
  await expect(page.getByRole('article')).toHaveCount(0);
  expect(state.conversation.title).toBe(employeeConversation.title);
  expect(unexpectedRequests).toEqual([]);
});

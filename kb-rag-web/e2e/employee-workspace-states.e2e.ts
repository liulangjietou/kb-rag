import { test, expect } from './fixtures';
import { employeeConversation, employeeFixture, employeeUrl } from './employee-workspace-fixture';

test.use({ grantedPermissions: ['app:use'] });

test('新建会话响应迟到时，不把已离开的用户拉回问答页', async ({ page, unexpectedRequests }) => {
  await employeeFixture(page);
  let accept!: () => void;
  await page.route('**/workspace/apps/app_fixture/conversations', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    await new Promise<void>((resolve) => { accept = resolve; });
    return route.fulfill({ json: { code: 'OK', data: { ...employeeConversation, conversation_id: 'conv_new', title: '新对话', last_turn: 0 } } });
  });
  await page.goto(employeeUrl);
  await page.getByRole('button', { name: /新对话$/ }).click();
  await expect.poll(() => typeof accept).toBe('function');
  await page.getByRole('menuitem', { name: /工作概览$/ }).click();
  await expect(page).toHaveURL(/\/home$/);
  const received = page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith('/conversations'));
  accept();
  await received;
  await expect(page.getByRole('heading', { name: /工作概览|你好|工作空间/ }).first()).toBeVisible();
  await expect(page).toHaveURL(/\/home$/);
  expect(unexpectedRequests).toEqual([]);
});

test('没有可用应用时给出下一步，避免空白问答框', async ({ page, unexpectedRequests }) => {
  await page.route('**/api/v1/workspace/apps', (route) => route.fulfill({ json: { code: 'OK', data: [] } }));
  await page.goto('/workspace');
  await expect(page.getByRole('heading', { name: '还没有可使用的正式应用' })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '输入知识问题' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /新对话$/ })).toBeDisabled();
  expect(unexpectedRequests).toEqual([]);
});

test('历史列表读取失败可以原位重试，当前已授权回答仍能阅读', async ({ page, unexpectedRequests }) => {
  await employeeFixture(page);
  let unavailable = true;
  await page.route('**/workspace/apps/app_fixture/conversations?*', (route) => unavailable
    ? route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '暂时无法读取历史' } }) : route.fallback());
  await page.goto(employeeUrl);
  const history = page.getByRole('complementary', { name: '会话历史', exact: true });
  await expect(history.getByText('会话加载失败')).toBeVisible();
  await expect(page.getByRole('table')).toBeVisible();
  unavailable = false;
  await history.getByRole('button', { name: /^重\s*试$/ }).click();
  await expect(history.getByRole('button', { name: /售后政策与处理流程/ })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

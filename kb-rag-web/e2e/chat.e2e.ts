import type { Route } from '@playwright/test';
import { test, expect } from './fixtures';

test('问答生成中不能通过回车重复发送', async ({ page, unexpectedRequests }) => {
  let requests = 0;
  let pendingRoute: Route | undefined;
  await page.route('**/api/v1/apps/app_fixture/chat-preview', (route) => {
    requests += 1;
    pendingRoute = route;
  });
  await page.goto('/chat');
  const input = page.getByPlaceholder('输入问题，回车发送（Shift+回车换行）');
  await input.fill('第一条问题');
  await input.press('Enter');
  await expect.poll(() => requests).toBe(1);
  await input.fill('正在生成时输入的下一条问题');
  await input.press('Enter');
  await expect(input).toHaveValue('正在生成时输入的下一条问题');
  expect(requests).toBe(1);
  await page.getByRole('button', { name: '停止生成' }).click();
  await expect(page.getByText('已停止生成', { exact: true })).toBeVisible();
  await pendingRoute?.abort();
  expect(unexpectedRequests).toEqual([]);
});

test('中文输入法确认候选词时不发送问题', async ({ page, unexpectedRequests }) => {
  let requests = 0;
  await page.route('**/api/v1/apps/app_fixture/chat-preview', async (route) => {
    requests += 1;
    await route.fulfill({ json: { code: 'OK', data: {} } });
  });
  await page.goto('/chat');
  const input = page.getByPlaceholder('输入问题，回车发送（Shift+回车换行）');
  await input.fill('还在输入的中文');
  await input.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true });
  await expect(input).toHaveValue('还在输入的中文');
  expect(requests).toBe(0);
  expect(unexpectedRequests).toEqual([]);
});

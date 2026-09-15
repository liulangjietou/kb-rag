import type { Route } from '@playwright/test';
import { test, expect } from './fixtures';
import { PERMISSIONS } from '../src/auth/permissions';

test.describe('最小权限与预览版本契约', () => {
  test.use({ grantedPermissions: [PERMISSIONS.SEARCH_DEBUG] });

  test('按真实版本 ID 调试当前语料，切换版本开始新对话', async ({ page, api, unexpectedRequests }) => {
    api['/app-previews'] = [{
      app_id: 'app_fixture', name: '客户服务助手', versions: [
        { app_version_id: 'draft_2', version: 'v2', status: 'DRAFT' },
        { app_version_id: 'release_1', version: 'v1', status: 'RELEASED' },
      ],
    }];
    let payload: Record<string, unknown> | undefined;
    await page.route('**/api/v1/apps/app_fixture/chat-preview', async (route) => {
      payload = route.request().postDataJSON();
      await route.fulfill({ contentType: 'text/event-stream', body: 'event: message_delta\ndata: {"delta":"版本回答"}\n\nevent: done\ndata: {"request_id":"req-version"}\n\n' });
    });
    await page.goto('/chat');
    await expect(page.getByText('当前语料', { exact: true })).toBeVisible();
    await page.getByRole('combobox', { name: '应用版本' }).press('ArrowDown');
    await page.getByText('v1 · 已发布', { exact: true }).click();
    await page.getByPlaceholder('输入问题，回车发送（Shift+回车换行）').fill('核对旧版本');
    await page.getByRole('button', { name: '发送问题' }).click();
    await expect.poll(() => payload?.app_version_id).toBe('release_1');
    expect(payload).not.toHaveProperty('app_version');
    expect(payload).not.toHaveProperty('app_id');
    await expect(page.getByText('版本回答', { exact: true })).toBeVisible();
    await page.getByRole('combobox', { name: '应用版本' }).press('ArrowDown');
    await page.getByText('v2 · 草稿', { exact: true }).click();
    await expect(page.getByText('版本回答', { exact: true })).toHaveCount(0);
    expect(unexpectedRequests).toEqual([]);
  });
});

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

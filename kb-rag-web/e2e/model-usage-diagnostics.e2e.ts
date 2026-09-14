import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

async function openUsage(page: Page, api: Record<string, unknown>) {
  api['/tenants'] = [{ tenant_id: 'usage-tenant', name: '用量验收租户', code: 'usage', status: 'ENABLED', builtin: false, monthly_token_quota: 10000, created_at: '2026-09-01T00:00:00' }];
  api['/model-usage/summary'] = { tenant_id: 'usage-tenant', month: '2026-09', quota_tokens: 10000,
    used_tokens: 3056, reserved_tokens: 500, remaining_tokens: 6444, estimated_calls: 0, unpriced_calls: 0, costs: [{ currency: 'CNY', cost_micros: 1234 }] };
  api['/model-usage/records'] = { page: 1, size: 20, total: 2, items: [
    { usage_id: 'truncated', tenant_id: 'usage-tenant', request_id: 'request-truncated', source: 'CONSOLE', source_id: 'source-safe',
      provider: 'dashscope', capability: 'CHAT', model: 'truncated-model', status: 'FAILED', reserved_tokens: 4096,
      input_tokens: 3024, output_tokens: 32, total_tokens: 3056, estimated: false, priced: true, currency: 'CNY', cost_micros: 1234,
      error_type: 'OUTPUT_TRUNCATED', created_at: '2026-09-14T12:00:00', completed_at: '2026-09-14T12:00:02' },
    { usage_id: 'pending', tenant_id: 'usage-tenant', request_id: 'request-pending', source: 'CONSOLE', source_id: 'source-safe',
      provider: 'dashscope', capability: 'CHAT', model: 'pending-model', status: 'RESERVED', reserved_tokens: 500,
      input_tokens: 0, output_tokens: 0, total_tokens: 0, estimated: false, priced: false, currency: null, cost_micros: 0,
      error_type: null, created_at: '2026-09-14T12:01:00', completed_at: null },
  ] };
  await page.goto('/settings/tenants');
  await page.getByText('用量', { exact: true }).click();
  await expect(page.getByText('truncated-model', { exact: true })).toBeVisible();
}

test('截断失败仍保留实际输入输出用量，预占单独显示', async ({ page, api, unexpectedRequests }) => {
  await openUsage(page, api);
  await expect(page.getByText('预占 500', { exact: true })).toBeVisible();
  await expect(page.getByText('待结算', { exact: true })).toBeVisible();
  await page.getByRole('row').filter({ hasText: 'truncated-model' }).getByRole('button', { name: '调用详情', exact: true }).click();
  await expect(page.getByText('3,024 Token', { exact: true })).toBeVisible();
  await expect(page.getByText('32 Token', { exact: true })).toBeVisible();
  await expect(page.getByText('模型达到了输出长度上限，返回内容不完整。请缩小问题范围或核对输出预算。')).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('刷新失败立即清除旧用量，不继续显示旧记录或无调用结论', async ({ page, api, unexpectedRequests }) => {
  await openUsage(page, api);
  await page.route('**/api/v1/model-usage/records?*', route => route.fulfill({ status: 500, json: { code: 'INTERNAL_ERROR', message: '服务不可用' } }));
  await page.getByRole('button', { name: '刷新用量', exact: true }).click();
  await expect(page.getByText('用量读取失败，旧数字已清除。请刷新重试。')).toBeVisible();
  await expect(page.getByText('truncated-model', { exact: true })).toHaveCount(0);
  await expect(page.getByText('本月暂无模型调用记录')).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('清空月份时移除上一月份数据，并提示选择有效月份', async ({ page, api, unexpectedRequests }) => {
  await openUsage(page, api);
  await page.getByLabel('用量月份', { exact: true }).fill('');
  await expect(page.getByText('请选择有效的用量月份。')).toBeVisible();
  await expect(page.getByText('truncated-model', { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

for (const remaining of [null, undefined]) {
  test(`不限额租户兼容接口剩余额度为 ${remaining === null ? 'null' : '省略字段'}`, async ({ page, api, unexpectedRequests }) => {
    await openUsage(page, api);
    api['/model-usage/summary'] = { ...(api['/model-usage/summary'] as Record<string, unknown>), quota_tokens: 0, remaining_tokens: remaining };
    await page.getByRole('button', { name: '刷新用量', exact: true }).click();
    await expect(page.locator('.model-usage-summary').getByText('不限额', { exact: true })).toHaveCount(2);
    await expect(page.getByText('NaN', { exact: true })).toHaveCount(0);
    expect(unexpectedRequests).toEqual([]);
  });
}

for (const theme of ['atlas', 'ocean', 'violet', 'cinder', 'moss', 'rose', 'graphite', 'night']) {
  test(`用量诊断 · ${theme} · 390px`, async ({ page, api, unexpectedRequests }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.addInitScript(preset => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
    await openUsage(page, api);
    const trigger = page.getByRole('row').filter({ hasText: 'truncated-model' }).getByRole('button', { name: '调用详情', exact: true });
    await trigger.click();
    await expect(page.getByText('模型达到了输出长度上限，返回内容不完整。请缩小问题范围或核对输出预算。')).toBeInViewport();
    const details = await page.locator('.model-usage-call-details').boundingBox();
    expect(details).not.toBeNull();
    expect(details!.x).toBeGreaterThanOrEqual(0);
    expect(details!.x + details!.width).toBeLessThanOrEqual(390);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    const audit = await new AxeBuilder({ page }).include('.model-usage-call-details').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect(audit.violations.map(v => ({ id: v.id, targets: v.nodes.map(n => n.target) }))).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog', { name: 'truncated-model 调用详情', exact: true })).toHaveCount(0);
    await expect(trigger).toBeFocused();
    expect(unexpectedRequests).toEqual([]);
  });
}

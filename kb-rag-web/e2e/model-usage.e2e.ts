import { test, expect } from './fixtures';

test('停止生成与估算用量分别展示，未知价格不显示为零成本', async ({ page, api, unexpectedRequests }) => {
  Object.assign(api, {
    '/tenants': [{
      tenant_id: 'tenant_fixture', code: 'fixture', name: '企业测试租户', status: 'ENABLED',
      builtin: false, monthly_token_quota: 10_000, created_at: '2026-09-01T00:00:00',
    }],
    '/model-usage/summary': {
      tenant_id: 'tenant_fixture', month: '2026-09', quota_tokens: 10_000,
      used_tokens: 150, reserved_tokens: 0, remaining_tokens: 9850,
      estimated_calls: 1, unpriced_calls: 1, costs: [],
    },
    '/model-usage/records': {
      items: [{
        usage_id: 'usage_cancelled', tenant_id: 'tenant_fixture', request_id: 'cancelled-request',
        source: 'CONSOLE', source_id: 'user_fixture', provider: 'dashscope', capability: 'CHAT',
        model: 'fixture-model', status: 'CANCELLED', reserved_tokens: 150,
        input_tokens: 150, output_tokens: 0, total_tokens: 150,
        estimated: true, priced: false, currency: null, cost_micros: 0,
        error_type: 'CancellationException', created_at: '2026-09-11T00:00:00',
        completed_at: '2026-09-11T00:00:05',
      }], page: 1, size: 20, total: 1,
    },
  });
  await page.goto('/settings/tenants');
  await expect(page.getByText('企业测试租户', { exact: true })).toBeVisible();
  await page.getByText('用量', { exact: true }).click();
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByText('已停止', { exact: true })).toBeVisible();
  await expect(drawer.getByText('估算', { exact: true })).toBeVisible();
  await expect(drawer.getByText('未定价', { exact: true })).toBeVisible();
  await expect(drawer.getByText('本月有 1 次用量为保守估算，1 次未命中价格配置')).toBeVisible();
  await expect(drawer.getByText('CANCELLED', { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

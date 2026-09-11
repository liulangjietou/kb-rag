import { test, expect, pageData } from './fixtures';
import type { Route } from '@playwright/test';

test('旧洞察列表晚到时不能覆盖新筛选结果', async ({ page, api, unexpectedRequests }) => {
  api['/kb/kb_fixture/retrieval-feedback'] = pageData([]);
  api['/kb/kb_fixture/search-insights/stats'] = { total: 1, zero_hit_count: 1, zero_hit_rate: 1, degraded_count: 0, top_zero_hit_queries: [] };
  let old: Route | undefined;
  const entry = { insight_id: 'insight_new', query_digest: '新筛选摘要', source: 'CONSOLE', result_count: 0, top_score: null, zero_hit: true, degraded: [], created_at: '2026-09-11T10:00:00' };
  await page.route('**/api/v1/kb/kb_fixture/search-insights?*', async (route) => {
    if (!new URL(route.request().url()).searchParams.has('zero_hit')) { old = route; return; }
    await route.fulfill({ json: { code: 'OK', data: pageData([entry]) } });
  });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('tab', { name: '质量与反馈', exact: true }).click();
  await page.getByRole('tab', { name: '检索洞察', exact: true }).click();
  await expect.poll(() => Boolean(old)).toBe(true);
  await page.locator('.ant-select').filter({ hasText: '命中筛选' }).locator('.ant-select-selector').click();
  await page.locator('.ant-select-dropdown:visible').getByTitle('仅零命中', { exact: true }).click();
  await expect(page.getByText('新筛选摘要', { exact: true })).toBeVisible();
  const response = page.waitForResponse((result) => result.url() === old!.request().url());
  await old!.fulfill({ json: { code: 'OK', data: pageData([{ ...entry, query_digest: '旧筛选摘要' }]) } });
  await response;
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(page.getByText('新筛选摘要', { exact: true })).toBeVisible();
  await expect(page.getByText('旧筛选摘要', { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('洞察加载失败不能显示成真实零值，重试后恢复统计与列表', async ({ page, api, unexpectedRequests }) => {
  api['/kb/kb_fixture/retrieval-feedback'] = pageData([]);
  let fail = true;
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/v1/kb/kb_fixture/search-insights**', async (route) => {
    if (fail) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '暂时无法获取洞察' } });
    const data = new URL(route.request().url()).pathname.endsWith('/stats') ? {
      total: 123, zero_hit_count: 0, zero_hit_rate: 0, degraded_count: 0, top_zero_hit_queries: [],
    } : pageData([]);
    await route.fulfill({ json: { code: 'OK', data } });
  });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('tab', { name: '质量与反馈', exact: true }).click();
  await page.getByRole('tab', { name: '检索洞察', exact: true }).click();
  await expect(page.getByText('统计加载失败', { exact: true })).toBeVisible();
  await expect(page.getByText('检索记录加载失败', { exact: true })).toBeVisible();
  const total = page.locator('.ant-statistic').filter({ hasText: '总检索次数' }).locator('.ant-statistic-content');
  await expect(total).toHaveText('—');
  await expect(page.getByText('时间窗口内没有零命中检索', { exact: true })).toHaveCount(0);
  expect(errors).toEqual([]);
  fail = false;
  await page.getByRole('button', { name: '刷新洞察', exact: true }).click();
  await expect(total).toHaveText('123');
  await expect(page.getByText('时间窗口内没有零命中检索', { exact: true })).toBeVisible();
  await expect(page.getByText('统计加载失败', { exact: true })).toHaveCount(0);
  await expect(page.getByText('检索记录加载失败', { exact: true })).toHaveCount(0);
  await expect(page.getByText(/最近成功更新/)).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

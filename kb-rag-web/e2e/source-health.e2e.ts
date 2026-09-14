import AxeBuilder from '@axe-core/playwright';
import { test, expect, kb, pageData } from './fixtures';
import { PERMISSIONS } from '../src/auth/permissions';

const web = {
  source_id: 'web_one', kb_id: kb.kb_id, url: 'https://example.test/handbook', file_name: '服务手册.html',
  doc_id: 'doc_0', sync_enabled: true, render_js: false, last_fetch_status: 'FAILED',
  last_fetch_at: '2026-09-14T15:30:00', last_success_at: '2026-09-13T12:00:00', last_content_change_at: null,
  last_error: '合成抓取失败', created_at: '2026-09-01T00:00:00',
};
const external = {
  source_id: 'ext_one', kb_id: kb.kb_id, source_type: 's3', name: '交付手册来源', endpoint: 'https://example.test',
  bucket: 'synthetic', access_key: 'synthetic', secret_key: '******', sync_enabled: false,
  last_sync_status: 'PARTIAL', last_sync_at: '2026-09-14T15:30:00', last_success_at: null,
  last_content_change_at: '2026-09-14T15:31:00', last_error: '1 个对象失败', created_at: '2026-09-01T00:00:00',
};
const tasks = [
  ['PENDING_CONFIRM', '解析待确认', 'process_status', 'PENDING_CONFIRM'],
  ['PENDING_REVIEW', '文档待审核', 'publish_status', 'PENDING_REVIEW'],
  ['WEB_SOURCE_FAILED', '网页抓取失败', 'attention_only', 'true'],
  ['EXT_SOURCE_ATTENTION', '外部来源需处理', 'attention_only', 'true'],
] as const;

for (const [kind, label, key, value] of tasks) {
  test(`首页待办准确定位到 ${label} 并可重置筛选`, async ({ page, api, unexpectedRequests }) => {
    api['/me/knowledge-todos'] = [{ kb_id: kb.kb_id, kb_name: kb.name, kind, total: 2, can_process: true }];
    api[`/kb/${kb.kb_id}/web-sources`] = pageData([web]);
    api[`/kb/${kb.kb_id}/ext-sources`] = pageData([external]);
    const suffix = kind === 'WEB_SOURCE_FAILED' ? 'web-sources' : kind === 'EXT_SOURCE_ATTENTION' ? 'ext-sources' : 'documents';
    const queries: URLSearchParams[] = [];
    page.on('request', request => {
      const url = new URL(request.url());
      if (url.pathname === `/api/v1/kb/${kb.kb_id}/${suffix}`) queries.push(url.searchParams);
    });
    await page.goto('/home');
    await page.getByRole('region', { name: '知识处理待办' }).getByRole('button', { name: new RegExp(label) }).click();
    await expect(page).toHaveURL(new RegExp(`todo=${kind}$`));
    await expect.poll(() => queries.at(-1)?.get(key)).toBe(value);
    if (suffix === 'documents') {
      await page.getByRole('button', { name: '重置筛选', exact: true }).click();
    } else {
      const checkbox = page.getByRole('checkbox', { name: kind === 'WEB_SOURCE_FAILED' ? '仅看抓取失败' : '仅看失败或部分成功' });
      await expect(checkbox).toBeChecked();
      await checkbox.uncheck();
    }
    await expect.poll(() => queries.at(-1)?.has(key)).toBe(false);
    expect(unexpectedRequests).toEqual([]);
  });
}

for (const width of [390, 768, 1440]) {
  test(`待办与来源健康 ${width}px 保留主题、键盘和阅读空间`, async ({ page, api, unexpectedRequests }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    api['/me/knowledge-todos'] = [{ kb_id: kb.kb_id, kb_name: '服务规范与客户交付资料'.repeat(5), kind: 'WEB_SOURCE_FAILED', total: 123, can_process: false }];
    api[`/kb/${kb.kb_id}/web-sources`] = pageData([web]);
    await page.goto('/home');
    const row = page.getByRole('region', { name: '知识处理待办' }).getByRole('button', { name: /网页抓取失败/ });
    await expect(row).toBeVisible();
    expect((await new AxeBuilder({ page }).include('[aria-label="知识处理待办"]').analyze()).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    await page.screenshot({ path: testInfo.outputPath('home-todos.png'), fullPage: true });
    await row.focus(); await page.keyboard.press('Enter');
    await expect(page.getByRole('columnheader', { name: '同步记录' })).toBeVisible();
    const address = page.getByRole('link', { name: web.url, exact: true });
    await expect(address).toBeVisible();
    await expect(address).toBeInViewport();
    expect((await address.boundingBox())?.width).toBeGreaterThan(100);
    await expect(page.locator('.source-health-times').getByText('暂无记录')).toBeVisible();
    await expect(page.locator('.source-health-times time')).toHaveCount(2);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    await page.screenshot({ path: testInfo.outputPath('source-health.png'), fullPage: true });
    expect(unexpectedRequests).toEqual([]);
  });
}

for (const kind of ['WEB_SOURCE_FAILED', 'EXT_SOURCE_ATTENTION']) {
  test(`来源读取失败不会同时伪装成空列表 ${kind}`, async ({ page, unexpectedRequests }) => {
    const suffix = kind === 'WEB_SOURCE_FAILED' ? 'web-sources' : 'ext-sources';
    let failed = true;
    await page.route(`**/api/v1/kb/${kb.kb_id}/${suffix}?*`, route => failed
      ? route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '合成读取失败' } })
      : route.fulfill({ json: { code: 'OK', data: pageData([]) } }));
    await page.goto(`/kb/${kb.kb_id}?todo=${kind}`);
    await expect(page.getByRole('alert').filter({ hasText: '来源加载失败' })).toBeVisible();
    await expect(page.locator('.ant-table-placeholder')).toHaveCount(0);
    failed = false;
    await page.getByRole('button', { name: kind === 'WEB_SOURCE_FAILED' ? '刷新来源' : /^刷\s*新$/, exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: '来源加载失败' })).toHaveCount(0);
    await expect(page.locator('.ant-table-placeholder')).toBeVisible();
    expect(unexpectedRequests).toEqual([]);
  });
}

test.describe('只读知识库用户', () => {
  test.use({ grantedPermissions: [PERMISSIONS.KB_READ] });
  test('保留待办与来源状态查看，隐藏同步、设置和登记操作', async ({ page, api, unexpectedRequests }) => {
    api['/me/knowledge-todos'] = [{ kb_id: kb.kb_id, kb_name: kb.name, kind: 'WEB_SOURCE_FAILED', total: 1, can_process: false }];
    api[`/kb/${kb.kb_id}/web-sources`] = pageData([web]);
    await page.goto('/home');
    const row = page.getByRole('region', { name: '知识处理待办' }).getByRole('button', { name: /网页抓取失败/ });
    await expect(row).toContainText('查看');
    await row.click();
    await expect(page.locator('.source-health-times')).toBeVisible();
    await expect(page.getByRole('button', { name: '登记并抓取' })).toBeHidden();
    await expect(page.getByRole('button', { name: '立即同步' })).toHaveCount(0);
    for (const toggle of await page.getByRole('switch').all()) await expect(toggle).toBeDisabled();
    expect(unexpectedRequests).toEqual([]);
  });
});

import AxeBuilder from '@axe-core/playwright';
import { test, expect, kb, pageData } from './fixtures';
import { PERMISSIONS } from '../src/auth/permissions';

const source = {
  source_id: 'ext_retry', kb_id: kb.kb_id, source_type: 's3', name: '操作手册来源',
  endpoint: 'https://example.test', bucket: 'synthetic', access_key: 'synthetic', secret_key: '******',
  sync_enabled: false, last_sync_status: 'PARTIAL', last_sync_at: '2026-09-14T12:00:00',
  last_success_at: '2026-09-13T12:00:00', last_content_change_at: null,
  last_error: '1 个对象失败', created_at: '2026-09-01T00:00:00',
};
const failed = { object_key: '资料/交付手册与常见问题说明'.repeat(6) + '.md', etag: 'v1',
  doc_id: null, last_status: 'FAILED', last_error: '远端读取超时，请检查连接后重试',
  last_sync_at: '2026-09-14T12:00:00', created_at: '2026-09-01T00:00:00' };

test('对象明细提供失败筛选和只重试失败入口', async ({ page, api, unexpectedRequests }) => {
  api[`/kb/${kb.kb_id}/ext-sources`] = pageData([source]);
  let finished = false;
  let submitted = 0;
  const filters: boolean[] = [];
  await page.route('**/api/v1/ext-sources/ext_retry/items?*', async route => {
    const only = new URL(route.request().url()).searchParams.get('failed_only') === 'true';
    filters.push(only);
    const result = finished ? { ...failed, last_status: 'SUCCESS', last_error: null } : failed;
    await route.fulfill({ json: { code: 'OK', data: pageData(only && finished ? [] : [result]) } });
  });
  await page.route('**/api/v1/ext-sources/ext_retry/retry-failed', async route => {
    expect(route.request().method()).toBe('POST'); submitted++;
    await route.fulfill({ json: { code: 'OK', data: { accepted: true } } });
  });
  await page.goto(`/kb/${kb.kb_id}?todo=EXT_SOURCE_ATTENTION`);
  await page.getByRole('button', { name: '查看对象', exact: true }).click();
  const drawer = page.getByRole('dialog', { name: /同步明细/ });
  await expect(drawer.getByRole('checkbox', { name: '仅看失败对象' })).toBeChecked();
  await expect(drawer.getByRole('button', { name: '仅重试失败项' })).toBeVisible();
  await expect.poll(() => filters.at(-1)).toBe(true);
  await drawer.getByRole('button', { name: '仅重试失败项' }).click();
  await expect(drawer.getByText('失败项重试已提交')).toBeVisible();
  await expect(drawer.getByText(failed.last_error)).toBeVisible();
  expect(submitted).toBe(1);
  finished = true;
  await drawer.getByRole('button', { name: '刷新明细' }).click();
  await expect(drawer.getByRole('button', { name: '仅重试失败项' })).toBeDisabled();
  await drawer.getByRole('checkbox', { name: '仅看失败对象' }).uncheck();
  await expect.poll(() => filters.at(-1)).toBe(false);
  await expect(drawer.getByText(failed.last_error)).toHaveCount(0);
  await expect(drawer.getByText('已入库', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('对象读取失败与重试响应不确定各自显示，刷新后可恢复', async ({ page, api, unexpectedRequests }) => {
  api[`/kb/${kb.kb_id}/ext-sources`] = pageData([source]);
  let unavailable = true;
  await page.route('**/api/v1/ext-sources/ext_retry/items?*', route => unavailable
    ? route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '合成读取失败' } })
    : route.fulfill({ json: { code: 'OK', data: pageData([failed]) } }));
  await page.route('**/api/v1/ext-sources/ext_retry/retry-failed', route => route.abort('failed'));
  await page.goto(`/kb/${kb.kb_id}?todo=EXT_SOURCE_ATTENTION`);
  await page.getByRole('button', { name: '查看对象', exact: true }).click();
  const drawer = page.getByRole('dialog', { name: /同步明细/ });
  await expect(drawer.getByText('对象明细加载失败')).toBeVisible();
  await expect(drawer.locator('.ant-table-placeholder')).toHaveCount(0);
  await expect(drawer.getByRole('button', { name: '仅重试失败项' })).toBeDisabled();
  unavailable = false;
  await drawer.getByRole('button', { name: '刷新明细' }).click();
  await expect(drawer.getByText(failed.last_error)).toBeVisible();
  await drawer.getByRole('button', { name: '仅重试失败项' }).click();
  await expect(drawer.getByText('未确认重试是否受理')).toBeVisible();
  await expect(drawer.getByText('失败项重试已提交')).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

for (const [width, theme] of [[390, 'Night 夜航'], [768, 'Cinder 灰烬'], [1440, 'Atlas 翡翠']] as const) {
  test(`同步失败明细 ${width}px 原因可读并可键盘操作`, async ({ page, api, unexpectedRequests }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    api[`/kb/${kb.kb_id}/ext-sources`] = pageData([source]);
    api['/ext-sources/ext_retry/items'] = pageData([failed]);
    await page.goto(`/kb/${kb.kb_id}?todo=EXT_SOURCE_ATTENTION`);
    await page.getByRole('button', { name: '选择界面主题' }).click();
    await page.getByRole('menuitem', { name: new RegExp(theme) }).click();
    const open = page.getByRole('button', { name: '查看对象', exact: true });
    await open.focus(); await page.keyboard.press('Enter');
    const drawer = page.getByRole('dialog', { name: /同步明细/ });
    await expect(drawer.getByRole('button', { name: '仅重试失败项' })).toBeInViewport();
    await expect(drawer.getByText(failed.object_key)).toBeInViewport();
    await expect(drawer.getByText(failed.last_error)).toBeInViewport({ ratio: 1 });
    expect((await new AxeBuilder({ page }).include('.ext-source-results').analyze()).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    await page.screenshot({ path: testInfo.outputPath(`source-retry-${width}.png`), fullPage: true });
    if (width === 390) {
      await drawer.getByRole('row').filter({ hasText: failed.object_key }).focus();
      await page.keyboard.press('ArrowRight');
      await expect.poll(() => drawer.locator('.ant-table-content').evaluate(node => node.scrollLeft)).toBeGreaterThan(0);
    }
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await expect(open).toBeFocused();
    expect(unexpectedRequests).toEqual([]);
  });
}

test.describe('只读对象结果', () => {
  test.use({ grantedPermissions: [PERMISSIONS.KB_READ] });
  test('只读用户能查看原因和刷新，不能提交重试', async ({ page, api, unexpectedRequests }) => {
    api[`/kb/${kb.kb_id}/ext-sources`] = pageData([source]);
    api['/ext-sources/ext_retry/items'] = pageData([failed]);
    await page.goto(`/kb/${kb.kb_id}?todo=EXT_SOURCE_ATTENTION`);
    await page.getByRole('button', { name: '查看对象', exact: true }).click();
    const drawer = page.getByRole('dialog', { name: /同步明细/ });
    await expect(drawer.getByText(failed.last_error)).toBeVisible();
    await expect(drawer.getByRole('button', { name: '仅重试失败项' })).toHaveCount(0);
    await expect(drawer.getByRole('button', { name: '刷新明细' })).toBeEnabled();
    expect(unexpectedRequests).toEqual([]);
  });
});

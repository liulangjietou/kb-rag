import AxeBuilder from '@axe-core/playwright';
import type { Route } from '@playwright/test';
import { PERMISSIONS } from '../src/auth/permissions';
import { test, expect, documents, kb, pageData } from './fixtures';

for (const [width, theme] of [[390, 'Night 夜航'], [768, 'Cinder 灰烬'], [1440, 'Atlas 翡翠']] as const) {
  test(`上传结果 ${width}px 长文件名、主题与键盘恢复`, async ({ page, unexpectedRequests }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto('/kb/kb_fixture');
    await page.getByRole('button', { name: '选择界面主题' }).click();
    await page.getByRole('menuitem', { name: new RegExp(theme) }).click();
    await page.route('**/api/v1/kb/kb_fixture/documents', route => route.fulfill({
      status: 503, json: { code: 'UNAVAILABLE', message: '合成连接失败，请稍后重试。' },
    }));
    const trigger = page.getByRole('button', { name: '添加文档' });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const drawer = page.getByRole('dialog', { name: '添加文档' });
    const name = `${'交付规范与业务说明'.repeat(12)}.md`;
    await drawer.locator('input[type=file]').setInputFiles({ name, mimeType: 'text/markdown', buffer: Buffer.from('synthetic') });
    const retry = drawer.getByRole('button', { name: `重试 ${name}`, exact: true });
    await expect(retry).toBeVisible();
    await expect(retry).toBeInViewport();
    expect((await new AxeBuilder({ page }).include('.ant-drawer-content').analyze()).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    expect(await drawer.evaluate(node => node.scrollWidth - node.clientWidth)).toBeLessThanOrEqual(0);
    await retry.focus();
    await page.keyboard.press('Tab');
    await expect(drawer.getByRole('button', { name: `移除 ${name}`, exact: true })).toBeFocused();
    await page.screenshot({ path: testInfo.outputPath('upload-results.png'), fullPage: true });
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await expect(trigger).toBeFocused();
    expect(unexpectedRequests).toEqual([]);
  });
}

test.describe('上传入口权限', () => {
  test.use({ grantedPermissions: [PERMISSIONS.KB_READ] });
  test('只读角色没有上传入口或可用文件控件', async ({ page, unexpectedRequests }) => {
    await page.goto('/kb/kb_fixture');
    await expect(page.getByRole('button', { name: '添加文档' })).toHaveCount(0);
    await expect(page.locator('input[type=file]')).toHaveCount(0);
    expect(unexpectedRequests).toEqual([]);
  });
});

test('切换知识库丢弃旧队列和迟到结果，未启动文件不会继续发送', async ({ page, api, unexpectedRequests }) => {
  api['/kb/kb_other'] = { ...kb, kb_id: 'kb_other', name: '另一知识库' };
  api['/kb/kb_other/documents'] = pageData([]);
  api['/kb/kb_other/rebuild-status'] = { stale_count: 0, in_progress_count: 0, failed_count: 0, processing_count: 0, document_count: 0 };
  const pending: Route[] = [];
  await page.route('**/api/v1/kb/kb_fixture/documents', route => { pending.push(route); });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('button', { name: '添加文档' }).click();
  const drawer = page.getByRole('dialog', { name: '添加文档' });
  await drawer.locator('input[type=file]').setInputFiles(['a', 'b', 'c'].map(name => ({
    name: `${name}.md`, mimeType: 'text/markdown', buffer: Buffer.from(name),
  })));
  await expect.poll(() => pending.length).toBe(2);
  await page.evaluate(() => { history.pushState({}, '', '/kb/kb_other'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await expect(page.getByRole('heading', { name: '另一知识库', exact: true })).toBeVisible();
  const responses = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/kb/kb_fixture/documents');
  for (const route of pending) await route.fulfill({ json: { code: 'OK', data: documents[0] } });
  await responses;
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  expect(pending).toHaveLength(2);
  await expect(page.getByText('a.md', { exact: true })).toHaveCount(0);
  await expect(page.getByText('c.md', { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

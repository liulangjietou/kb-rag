import AxeBuilder from '@axe-core/playwright';
import { test, expect, kb, app } from './fixtures';

// 所有记录均为接口夹具；不访问真实业务服务或认证挑战。
test('成功打开才记录，首页按实际访问顺序展示且可清空后重新积累', async ({ page, api, unexpectedRequests }) => {
  api['/me/resource-visits'] = [];
  const writes: unknown[] = [];
  let documentReads = 0;
  await page.clock.install();
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === `/api/v1/kb/${kb.kb_id}/documents`) documentReads += 1;
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/me/resource-visits') writes.push(request.postDataJSON());
  });
  await page.goto('/home');
  await expect(page.getByText('打开知识库或应用后，会在这里显示最近访问')).toBeVisible();
  expect(writes).toEqual([]);
  await page.goto(`/kb/${kb.kb_id}`);
  await expect(page.getByText('售后服务政策.docx', { exact: true })).toBeVisible();
  await expect.poll(() => writes).toEqual([{ resource_type: 'KB', resource_id: kb.kb_id }]);
  // 后台轮询触发重新渲染，但不应被记成新导航。
  const initialReads = documentReads;
  await page.clock.fastForward(31000);
  await expect.poll(() => documentReads).toBeGreaterThan(initialReads);
  await expect.poll(() => writes.length).toBe(1);
  await page.goto(`/apps/${app.app_id}`);
  await expect(page.getByText('关联知识库', { exact: true })).toBeVisible();
  await expect.poll(() => writes.length).toBe(2);
  await page.goto('/home');
  const recent = page.getByRole('region', { name: '最近资源访问' });
  await expect(recent.locator('.atlas-work-list strong')).toHaveText([app.name, kb.name]);
  await expect(recent.getByText(/访问于/)).toHaveCount(2);
  await recent.getByRole('button', { name: '清空记录', exact: true }).click();
  await page.getByRole('button', { name: /^清\s*空$/ }).click();
  await expect(page.getByText('打开知识库或应用后，会在这里显示最近访问')).toBeVisible();
  await page.reload();
  await expect(page.getByText('打开知识库或应用后，会在这里显示最近访问')).toBeVisible();
  await page.goto(`/kb/${kb.kb_id}`);
  await expect.poll(() => writes.length).toBe(3);
  await page.goto('/home');
  await expect(recent.locator('.atlas-work-list strong')).toHaveText([kb.name]);
  expect(unexpectedRequests).toEqual([]);
});

test('辅助写入失败不干扰详情，读取失败和真实空态分开', async ({ page, unexpectedRequests }) => {
  await page.route('**/api/v1/me/resource-visits', (route) => route.fulfill({ status: 503, json: { code: 'SYNTHETIC_FAILURE', data: null } }));
  await page.goto(`/apps/${app.app_id}`);
  await expect(page.getByText('关联知识库', { exact: true })).toBeVisible();
  await expect(page.locator('.ant-message-notice-error')).toHaveCount(0);
  await page.goto('/home');
  await expect(page.getByText('访问记录加载失败，请刷新重试。')).toBeVisible();
  await expect(page.getByText('打开知识库或应用后，会在这里显示最近访问')).toHaveCount(0);
  await page.unroute('**/api/v1/me/resource-visits');
  await page.getByRole('button', { name: '刷新记录', exact: true }).click();
  await expect(page.getByRole('region', { name: '最近资源访问' }).getByText(kb.name, { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

for (const width of [390, 768, 1440]) {
  test(`最近访问在 ${width} 宽度下支持键盘操作且无横向溢出`, async ({ page, unexpectedRequests }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto('/home');
    const recent = page.getByRole('region', { name: '最近资源访问' });
    const row = recent.getByRole('button', { name: new RegExp(kb.name) });
    await expect(row).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    const accessibility = await new AxeBuilder({ page }).include('[aria-label="最近资源访问"]').analyze();
    expect(accessibility.violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath('recent-visits.png'), fullPage: true });
    await row.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/kb/${kb.kb_id}$`));
    expect(unexpectedRequests).toEqual([]);
  });
}

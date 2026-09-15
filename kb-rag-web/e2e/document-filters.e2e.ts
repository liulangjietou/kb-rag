import { test, expect, documents, kb, pageData } from './fixtures';

test('同一详情组件切换知识库后清空筛选，不把另一库的零待重建误报为完成', async ({ page, api, unexpectedRequests }) => {
  api['/kb/kb_other'] = { ...kb, kb_id: 'kb_other', name: '另一知识库' };
  api['/kb/kb_other/documents'] = pageData([]);
  api['/kb/kb_other/rebuild-status'] = { stale_count: 0, in_progress_count: 0, failed_count: 0, processing_count: 0, document_count: 0 };
  await page.goto('/kb/kb_fixture');
  await expect(page.getByText('1 篇文档使用旧配置', { exact: true })).toBeVisible();
  await page.getByRole('textbox', { name: '文档名称' }).fill('产品');
  await page.getByRole('button', { name: '筛选文档', exact: true }).click();
  await expect(page.getByRole('button', { name: '刷新文档' })).not.toHaveClass(/ant-btn-loading/);
  // 模拟浏览器在两个详情历史条目间导航，保留 React 组件实例以覆盖参数变化。
  await page.evaluate(() => {
    document.body.dataset.falseCompletion = 'false';
    new MutationObserver(() => {
      if (document.body.textContent?.includes('重建完成，新分片配置已生效')) document.body.dataset.falseCompletion = 'true';
    }).observe(document.body, { childList: true, subtree: true, characterData: true });
    history.pushState({}, '', '/kb/kb_other');
    dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page.getByRole('heading', { name: '另一知识库' })).toBeVisible();
  await expect(page.getByRole('tab', { name: '文档（0）', exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '文档名称' })).toHaveValue('');
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  // 记录整个切换过程，不能等错误 toast 自动消失后把“最终不存在”误判为从未出现。
  expect(await page.locator('body').getAttribute('data-false-completion')).toBe('false');
  expect(unexpectedRequests).toEqual([]);
});

test('中文组词不提交，日期范围按本地完整日期传给服务端', async ({ page, unexpectedRequests }) => {
  let filtered: URLSearchParams | undefined;
  await page.route('**/api/v1/kb/kb_fixture/documents?*', async (route) => {
    const query = new URL(route.request().url()).searchParams;
    if (query.has('keyword')) filtered = query;
    await route.fulfill({ json: { code: 'OK', data: { items: [], total: 0, page: 1, size: 10 } } });
  });
  await page.goto('/kb/kb_fixture');
  const name = page.getByRole('textbox', { name: '文档名称' });
  await name.dispatchEvent('compositionstart', { data: '报' });
  await name.fill('报销');
  await name.press('Enter');
  expect(filtered).toBeUndefined();
  await name.dispatchEvent('compositionend', { data: '报销' });
  await page.getByPlaceholder('开始日期', { exact: true }).fill('2026-09-01');
  await page.getByPlaceholder('开始日期', { exact: true }).press('Tab');
  await page.getByPlaceholder('结束日期', { exact: true }).fill('2026-09-11');
  await page.getByPlaceholder('结束日期', { exact: true }).press('Enter');
  await page.getByRole('button', { name: '筛选文档', exact: true }).click();
  await expect.poll(() => filtered?.get('updated_from')).toBe('2026-09-01T00:00:00');
  expect(filtered?.get('updated_to')).toBe('2026-09-11T23:59:59.999');
  expect(unexpectedRequests).toEqual([]);
});

for (const width of [390, 768, 1920]) {
  test(`文档筛选布局与重置操作 · ${width}px`, async ({ page, unexpectedRequests }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto('/kb/kb_fixture');
    await expect(page.getByText(/文档列表更新于/)).toBeVisible();
    if (width === 390) {
      await expect(page.getByRole('combobox', { name: '文档来源筛选' })).toBeHidden();
      await page.getByRole('button', { name: '更多筛选', exact: true }).click();
      await expect(page.getByRole('combobox', { name: '文档来源筛选' })).toBeVisible();
      await page.getByRole('button', { name: '收起筛选', exact: true }).click();
    }
    await page.getByRole('textbox', { name: '文档名称' }).fill('产品');
    await page.getByRole('button', { name: '重置筛选', exact: true }).click();
    await expect(page.getByRole('textbox', { name: '文档名称' })).toHaveValue('');
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    await page.screenshot({ path: testInfo.outputPath('document-filters.png'), fullPage: true });
    expect(unexpectedRequests).toEqual([]);
  });
}

test('筛选提交到服务端并回到首页，翻页保留条件且清空勾选', async ({ page, unexpectedRequests }) => {
  const queries: URLSearchParams[] = [];
  await page.route('**/api/v1/kb/kb_fixture/documents?*', async (route) => {
    const query = new URL(route.request().url()).searchParams;
    queries.push(query);
    const number = Number(query.get('page'));
    await route.fulfill({ json: { code: 'OK', data: {
      items: [{ ...documents[0], file_name: query.get('keyword') ? `筛选结果第${number}页.pdf` : '产品使用手册.pdf' }],
      page: number, size: 10, total: 11,
    } } });
  });
  await page.goto('/kb/kb_fixture');
  await page.locator('.ant-pagination-item-2').click();
  await page.getByRole('checkbox', { name: '选择 产品使用手册.pdf' }).check();
  await page.getByRole('textbox', { name: '文档名称' }).fill('报销%_!');
  await page.locator('.ant-select').filter({ has: page.getByRole('combobox', { name: '处理状态筛选' }) }).locator('.ant-select-selector').click();
  await page.locator('.ant-select-dropdown:visible').getByTitle('已就绪', { exact: true }).click();
  await page.locator('.ant-select').filter({ has: page.getByRole('combobox', { name: '发布状态筛选' }) }).locator('.ant-select-selector').click();
  await page.locator('.ant-select-dropdown:visible').getByTitle('已发布', { exact: true }).click();
  await page.locator('.ant-select').filter({ has: page.getByRole('combobox', { name: '文档来源筛选' }) }).locator('.ant-select-selector').click();
  await page.locator('.ant-select-dropdown:visible').getByTitle('网页导入', { exact: true }).click();
  await page.getByRole('button', { name: '筛选文档', exact: true }).click();
  await expect(page.getByText('筛选结果第1页.pdf', { exact: true })).toBeVisible();
  expect(Object.fromEntries(queries.at(-1)!)).toMatchObject({
    keyword: '报销%_!', process_status: 'INDEXED', publish_status: 'PUBLISHED', source: 'WEB', page: '1',
  });
  await expect(page.getByRole('alert').filter({ hasText: '已选中' })).toHaveCount(0);
  await page.locator('.ant-pagination-item-2').click();
  await expect(page.getByText('筛选结果第2页.pdf', { exact: true })).toBeVisible();
  expect(queries.at(-1)!.get('keyword')).toBe('报销%_!');
  await page.getByRole('button', { name: '重置筛选', exact: true }).click();
  await expect(page.getByText('产品使用手册.pdf', { exact: true })).toBeVisible();
  expect(queries.at(-1)!.has('keyword')).toBe(false);
  await expect(page.locator('.ant-pagination-item-active')).toHaveText('1');
  expect(unexpectedRequests).toEqual([]);
});

test('查询失败不会伪装成空库，重试保留筛选并显示真正的无匹配状态', async ({ page, unexpectedRequests }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  let fail = true;
  await page.route('**/api/v1/kb/kb_fixture/documents?*', async (route) => {
    const query = new URL(route.request().url()).searchParams;
    if (!query.has('keyword')) return route.fallback();
    if (fail) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '暂时不可用' } });
    await route.fulfill({ json: { code: 'OK', data: { items: [], total: 0, page: 1, size: 10 } } });
  });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('textbox', { name: '文档名称' }).fill('未收录手册');
  await page.getByRole('button', { name: '筛选文档', exact: true }).click();
  const error = page.getByRole('alert').filter({ hasText: '文档加载失败' });
  await expect(error).toBeVisible();
  await expect(page.getByText('暂无文档', { exact: true })).toHaveCount(0);
  await expect(page.getByText('共 0 个文档', { exact: true })).toHaveCount(0);
  fail = false;
  await error.getByRole('button', { name: '重试' }).click();
  await expect(page.getByText('没有符合条件的文档，请调整或重置筛选', { exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '文档名称' })).toHaveValue('未收录手册');
  expect(errors).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

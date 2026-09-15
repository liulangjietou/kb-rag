import type { Route } from '@playwright/test';
import { test, expect, documents, pageData } from './fixtures';

test('同一知识库实际完成重建时仍提示完成', async ({ page, api, unexpectedRequests }) => {
  api['/kb/kb_fixture/rebuild-status'] = { stale_count: 1, in_progress_count: 1, failed_count: 0, processing_count: 1, document_count: 5 };
  await page.clock.install();
  await page.goto('/kb/kb_fixture');
  await expect(page.getByText('1 篇文档使用旧配置', { exact: true })).toBeVisible();
  api['/kb/kb_fixture/rebuild-status'] = { stale_count: 0, in_progress_count: 0, failed_count: 0, processing_count: 0, document_count: 5 };
  await page.clock.fastForward(3100);
  await expect(page.getByText('重建完成，新分片配置已生效', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('空闲时降低刷新频率，隐藏页面暂停，恢复时立即同步', async ({ page, api, unexpectedRequests }) => {
  api['/kb/kb_fixture/rebuild-status'] = { stale_count: 0, in_progress_count: 0, failed_count: 0, processing_count: 0, document_count: 5 };
  let requests = 0;
  await page.route('**/api/v1/kb/kb_fixture/documents?*', async (route) => {
    requests += 1;
    await route.fulfill({ json: { code: 'OK', data: pageData(documents) } });
  });
  await page.clock.install();
  await page.goto('/kb/kb_fixture');
  await expect(page.getByText(/文档列表更新于/)).toBeVisible();
  const initial = requests;
  await page.clock.fastForward(10000);
  expect(requests).toBe(initial);
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.clock.fastForward(60000);
  expect(requests).toBe(initial);
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect.poll(() => requests).toBe(initial + 1);
  expect(unexpectedRequests).toEqual([]);
});

test('全库其他页仍在处理时持续同步，慢请求不叠加，失败后退避', async ({ page, api, unexpectedRequests }) => {
  api['/kb/kb_fixture/rebuild-status'] = { stale_count: 0, in_progress_count: 0, failed_count: 0, processing_count: 1, document_count: 100 };
  let requests = 0;
  let pending: Route | undefined;
  let holdNext = false;
  await page.route('**/api/v1/kb/kb_fixture/documents?*', async (route) => {
    requests += 1;
    if (holdNext) { holdNext = false; pending = route; return; }
    await route.fulfill({ json: { code: 'OK', data: pageData(documents) } });
  });
  await page.clock.install();
  await page.goto('/kb/kb_fixture');
  await expect(page.getByText(/文档列表更新于/)).toBeVisible();
  const initial = requests;
  holdNext = true;
  await page.clock.fastForward(3100);
  await expect.poll(() => Boolean(pending)).toBe(true);
  await page.clock.fastForward(20000);
  expect(requests).toBe(initial + 1);
  await pending!.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '连接失败' } });
  await expect(page.getByText('文档加载失败', { exact: true })).toBeVisible();
  await page.clock.fastForward(3100);
  expect(requests).toBe(initial + 1);
  await page.clock.fastForward(3100);
  await expect.poll(() => requests).toBe(initial + 2);
  await expect(page.getByText('文档加载失败', { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

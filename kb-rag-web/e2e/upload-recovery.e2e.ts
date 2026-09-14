import { test, expect, documents } from './fixtures';

test('批量上传保留部分成功结果，只重试失败文件，抽屉重开后仍可核对', async ({ page, unexpectedRequests }) => {
  const calls: string[] = [];
  let retryAllowed = false;
  await page.route('**/api/v1/kb/kb_fixture/documents', async (route) => {
    const body = route.request().postDataBuffer()?.toString() ?? '';
    const name = /filename="([^"]+)"/.exec(body)?.[1] ?? '';
    calls.push(name);
    if (name === 'bad.md' && !retryAllowed) {
      return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '合成上传失败' } });
    }
    await route.fulfill({ json: { code: 'OK', data: { ...documents[0], file_name: name, process_status: 'UPLOADED', version: 'V1', duplicated: false } } });
  });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('button', { name: '添加文档' }).click();
  const drawer = page.getByRole('dialog', { name: '添加文档' });
  await drawer.locator('input[type=file]').setInputFiles([
    { name: 'good.md', mimeType: 'text/markdown', buffer: Buffer.from('good synthetic content') },
    { name: 'bad.md', mimeType: 'text/markdown', buffer: Buffer.from('bad synthetic content') },
  ]);
  await expect.poll(() => calls.length).toBe(2);
  const records = drawer.getByRole('region', { name: '本次上传记录' });
  await expect(records).toBeVisible();
  await expect(records.getByText('good.md', { exact: true })).toBeVisible();
  await expect(records.getByText('bad.md', { exact: true })).toBeVisible();
  await expect(records.getByRole('button', { name: '重试 bad.md', exact: true })).toBeVisible();
  await drawer.getByRole('button', { name: /close|关闭/i }).click();
  await page.getByRole('button', { name: '添加文档' }).click();
  await expect(records.getByText('bad.md', { exact: true })).toBeVisible();
  retryAllowed = true;
  await records.getByRole('button', { name: '仅重试失败文件' }).click();
  await expect.poll(() => calls.filter(name => name === 'bad.md').length).toBe(2);
  expect(calls.filter(name => name === 'good.md')).toHaveLength(1);
  await expect(records.getByRole('button', { name: '重试 bad.md', exact: true })).toHaveCount(0);
  await expect(records.getByText('已接收', { exact: true })).toHaveCount(2);
  expect(unexpectedRequests).toEqual([]);
});

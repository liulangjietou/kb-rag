import { test, expect } from './fixtures';
import { employeeFixture, employeeUrl } from './employee-workspace-fixture';

test('模型状态横幅出现时，员工问答仍保留输入区且不产生整页滚动', async ({ page, unexpectedRequests }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await employeeFixture(page);
  await page.route('**/api/v1/system/model-status', (route) => route.fulfill({ json: { code: 'OK', data: {
    embedding_configured: false, chat_configured: true, rerank_configured: false, vector_engine: 'elasticsearch',
  } } }));
  await page.goto(employeeUrl);
  await expect(page.getByText('未配置嵌入模型，当前为 BM25 单路检索模式')).toBeVisible();
  await expect(page.getByRole('textbox', { name: '输入知识问题' })).toBeEnabled();
  expect(await page.evaluate(() => document.documentElement.scrollHeight - innerHeight)).toBeLessThanOrEqual(0);
  const input = await page.locator('.conversation-composer').boundingBox();
  expect(input!.y + input!.height).toBeLessThanOrEqual(720);
  expect(unexpectedRequests).toEqual([]);
});

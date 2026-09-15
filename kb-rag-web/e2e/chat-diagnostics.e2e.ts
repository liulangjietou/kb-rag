import { THEME_IDS } from './theme-fixture';
import { test, expect } from './fixtures';
import AxeBuilder from '@axe-core/playwright';
import type { Page, Route } from '@playwright/test';

const measured = { request_id: 'req_diagnostics', outcome: 'SUCCEEDED', configuration_ms: 12,
  retrieval_ms: 240, generation_ms: 1300, first_delta_ms: 602, total_ms: 1552 };

async function answerWith(page: Page, body: string) {
  await page.route('**/api/v1/apps/app_fixture/chat-preview', route => route.fulfill({ contentType: 'text/event-stream', body }));
  await page.goto('/chat');
  await page.getByPlaceholder('输入问题，回车发送（Shift+回车换行）').fill('诊断这个回答');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect(page.locator('.chat-diagnostics')).toBeVisible();
}

test('问答调试展示服务端阶段耗时与浏览器接收耗时', async ({ page, unexpectedRequests }) => {
  await page.route('**/api/v1/apps/app_fixture/chat-preview', async (route) => {
    await route.fulfill({ contentType: 'text/event-stream', body: [
      'event: message_delta\ndata: {"delta":"有依据的回答"}',
      'event: diagnostics\ndata: {"request_id":"req_diagnostics","outcome":"SUCCEEDED","configuration_ms":12,"retrieval_ms":240,"generation_ms":1300,"first_delta_ms":602,"total_ms":1552}',
      'event: done\ndata: {"request_id":"req_diagnostics"}',
      '', '',
    ].join('\n\n') });
  });
  await page.goto('/chat');
  await page.getByPlaceholder('输入问题，回车发送（Shift+回车换行）').fill('诊断这个回答');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect(page.getByText('有依据的回答', { exact: true })).toBeVisible();
  await page.getByText('查看耗时诊断', { exact: true }).click();
  await expect(page.getByText('240 ms', { exact: true })).toBeVisible();
  await expect(page.getByText('1,300 ms', { exact: true })).toBeVisible();
  await expect(page.getByText('浏览器接收总耗时', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('检索失败保留阶段，未执行的生成不显示零或成功', async ({ page, unexpectedRequests }) => {
  await answerWith(page, `event: diagnostics\ndata: ${JSON.stringify({ outcome: 'FAILED', failed_stage: 'RETRIEVAL',
    configuration_ms: 0, retrieval_ms: 25, total_ms: 25 })}\n\nevent: error\ndata: {"code":"RETRIEVAL_FAILED","message":"检索失败"}\n\n`);
  await page.getByText('查看耗时诊断', { exact: true }).click();
  await expect(page.getByText('执行失败 · 结束于检索', { exact: true })).toBeVisible();
  await expect(page.locator('.chat-diagnostics__metrics div').filter({ has: page.getByText('生成', { exact: true }) })).toHaveText('生成未采集');
  await expect(page.getByText('0 ms', { exact: true })).toBeVisible();
  await expect(page.getByText('接收未完成', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('旧服务缺失诊断时仍可完成回答且不伪造阶段耗时', async ({ page, unexpectedRequests }) => {
  await answerWith(page, 'event: message_delta\ndata: {"delta":"旧服务回答"}\n\nevent: done\ndata: {"request_id":"req_old"}\n\n');
  await page.getByText('查看耗时诊断', { exact: true }).click();
  await expect(page.getByText('本次未返回服务端诊断，阶段耗时未知', { exact: true })).toBeVisible();
  await expect(page.getByText('旧服务回答', { exact: true })).toBeVisible();
  await expect(page.getByText('接收完成', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('非流式返回阶段诊断，首段等待明确不适用', async ({ page, unexpectedRequests }) => {
  await page.route('**/api/v1/apps/app_fixture/chat-preview', route => {
    expect(route.request().postDataJSON().stream).toBe(false);
    return route.fulfill({ json: { code: 'OK', data: { answer: '非流式回答', references: [], degraded: [],
      request_id: 'req_nonstream', diagnostics: { ...measured, first_delta_ms: null } } } });
  });
  await page.goto('/chat');
  await page.getByRole('switch', { name: '启用流式回答' }).click();
  await page.getByPlaceholder('输入问题，回车发送（Shift+回车换行）').fill('非流式问题');
  await page.getByRole('button', { name: '发送问题' }).click();
  await page.getByText('查看耗时诊断', { exact: true }).click();
  await expect(page.getByText('非流式不适用', { exact: true })).toBeVisible();
  await expect(page.getByText('240 ms', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('停止等待保留浏览器测量，不显示成功或伪造首段', async ({ page, unexpectedRequests }) => {
  let pending: Route | undefined;
  await page.route('**/api/v1/apps/app_fixture/chat-preview', route => { pending = route; });
  await page.goto('/chat');
  await page.getByPlaceholder('输入问题，回车发送（Shift+回车换行）').fill('停止问题');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect.poll(() => !!pending).toBe(true);
  await page.getByRole('button', { name: '停止生成' }).click();
  await page.getByText('查看耗时诊断', { exact: true }).click();
  await expect(page.getByText('已停止接收', { exact: true })).toBeVisible();
  await expect(page.getByText('未采集', { exact: true })).toBeVisible();
  await expect(page.getByText('接收完成', { exact: true })).toHaveCount(0);
  await pending?.abort();
  expect(unexpectedRequests).toEqual([]);
});

for (const theme of THEME_IDS) {
  for (const width of [390, 768, 1280, 1440, 1920]) {
    test(`问答耗时诊断 · ${theme} · ${width}px`, async ({ page, unexpectedRequests }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.addInitScript(preset => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
      await answerWith(page, `event: message_delta\ndata: {"delta":"回答"}\n\nevent: diagnostics\ndata: ${JSON.stringify(measured)}\n\nevent: done\ndata: {"request_id":"req_diagnostics"}\n\n`);
      const summary = page.locator('.chat-diagnostics summary');
      await summary.press('Enter');
      await expect(page.locator('.chat-diagnostics')).toHaveAttribute('open', '');
      await expect(page.getByText('1,300 ms', { exact: true })).toBeVisible();
      const bounds = await page.locator('.chat-diagnostics').boundingBox();
      expect(bounds).not.toBeNull();
      expect(bounds!.x).toBeGreaterThanOrEqual(0);
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
      expect(await page.locator('.chat-diagnostics').evaluate(el => el.scrollWidth > el.clientWidth + 1)).toBe(false);
      expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)).toBe(false);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      expect(await page.evaluate(() => localStorage.getItem('kb-rag-web:theme-preset'))).toBe(theme);
      if ((theme === 'atlas' && width === 390) || (theme === 'night' && width === 1440)) {
        await page.screenshot({ path: test.info().outputPath(`diagnostics-${theme}-${width}.png`), fullPage: true });
      }
      if (theme === 'atlas' && width === 390) {
        const audit = await new AxeBuilder({ page }).include('.chat-diagnostics').analyze();
        expect(audit.violations).toEqual([]);
      }
      await summary.press('Enter');
      await expect(page.locator('.chat-diagnostics')).not.toHaveAttribute('open', '');
      await expect(summary).toBeFocused();
      expect(unexpectedRequests).toEqual([]);
    });
  }
}

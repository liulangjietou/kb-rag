import { THEME_IDS } from './theme-fixture';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';
import { employeeFixture, employeeUrl } from './employee-workspace-fixture';

test.use({ grantedPermissions: ['app:use'] });

for (const theme of THEME_IDS) {
  for (const [width, height] of [[390, 844], [768, 1024], [1280, 720], [1440, 900], [1920, 1080]]) {
    test(`员工问答 · ${theme} · ${width}px 布局与可访问性`, async ({ page, unexpectedRequests }, testInfo) => {
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await page.setViewportSize({ width, height });
      await page.addInitScript((preset) => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
      await employeeFixture(page);
      await page.goto(employeeUrl);
      await expect(page.getByRole('table')).toBeVisible();
      await expect(page.getByRole('textbox', { name: '输入知识问题' })).toBeEnabled();
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth), '整页不能横向溢出').toBeLessThanOrEqual(0);
      expect(await page.evaluate(() => document.documentElement.scrollHeight - innerHeight), '标准窗口只在问答区滚动，不应出现第二条整页滚动条').toBeLessThanOrEqual(0);
      const composer = await page.locator('.conversation-composer').boundingBox();
      expect(composer!.y + composer!.height, '输入框和发送操作应保持在首屏内').toBeLessThanOrEqual(height);
      const pane = await page.locator('.conversation-pane').boundingBox();
      expect(pane!.width, '问答正文必须保留可读宽度').toBeGreaterThanOrEqual(width === 390 ? 330 : 300);
      if (width >= 1440) await expect(page.getByRole('complementary', { name: '当前回答依据' })).toBeVisible();
      else await expect(page.getByRole('button', { name: '打开回答依据' })).toBeVisible();
      if (width < 768) await expect(page.getByRole('button', { name: '打开会话历史' })).toBeVisible();
      const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(result.violations.map((violation) => ({ id: violation.id, nodes: violation.nodes.map((node) => ({ target: node.target, detail: node.failureSummary })) }))).toEqual([]);
      expect(errors).toEqual([]);
      expect(unexpectedRequests).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath('workspace.png'), fullPage: true });
    });
  }
}

test('390px 历史与引用抽屉可以独立打开关闭，焦点返回触发按钮', async ({ page, unexpectedRequests }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await employeeFixture(page);
  await page.goto(employeeUrl);
  const historyButton = page.getByRole('button', { name: '打开会话历史' });
  await historyButton.click();
  const history = page.getByRole('dialog');
  await history.getByRole('textbox', { name: '搜索历史会话' }).fill('售后');
  await expect.poll(() => state.searchQueries).toContain('售后');
  await history.getByRole('button', { name: /售后政策与处理流程/ }).click();
  await expect(history).toHaveCount(0);
  await expect(historyButton).toBeFocused();
  await page.getByRole('button', { name: '打开回答依据' }).click();
  const evidence = page.getByRole('dialog');
  await evidence.getByRole('button', { name: /引用 1 售后服务政策/ }).click();
  const reader = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: '本次引用片段' }) });
  await expect(reader).toBeVisible();
  await reader.getByRole('button', { name: '关闭', exact: true }).click();
  await expect(page.getByRole('heading', { name: '本次引用片段' })).toHaveCount(0);
  await page.getByRole('dialog').getByRole('button', { name: '关闭', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '打开回答依据' })).toBeFocused();
  expect(unexpectedRequests).toEqual([]);
});

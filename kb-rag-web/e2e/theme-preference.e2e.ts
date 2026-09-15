import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';

const storageKey = 'kb-rag-web:theme-preset';
const newPalettes = [
  ['ember', 'Ember 暖焰', 'light'],
  ['dawn', 'Dawn 晨曦', 'light'],
  ['aurora', 'Aurora 极光', 'dark'],
  ['graphite-dark', 'Graphite 墨岩（深色）', 'dark'],
] as const;

test('System 保留选择并响应系统明暗，手动选择后不再跟随', async ({ page, context, unexpectedRequests }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await page.goto('/home');
  const second = await context.newPage();
  await second.emulateMedia({ colorScheme: 'light' });
  await second.goto('/home');
  await page.getByRole('button', { name: '选择界面主题' }).click();
  await page.getByRole('menu').getByText('System 随行', { exact: true }).click();
  await expect(second.locator('html')).toHaveAttribute('data-theme-preference', 'system');
  await page.emulateMedia({ colorScheme: 'dark' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
  expect(await page.evaluate(key => localStorage.getItem(key), storageKey)).toBe('system');
  await expect(page.getByRole('status').filter({ hasText: '当前界面主题' })).toContainText('System 随行，当前深色');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
  await expect(page.locator('html')).toHaveAttribute('data-theme-preference', 'system');
  await page.emulateMedia({ colorScheme: 'light' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'atlas');
  await page.getByRole('button', { name: '选择界面主题' }).click();
  await page.getByRole('menu').getByText('Ember 暖焰', { exact: true }).click();
  await expect(second.locator('html')).toHaveAttribute('data-theme', 'ember');
  await page.emulateMedia({ colorScheme: 'dark' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'ember');
  expect(await page.evaluate(key => localStorage.getItem(key), storageKey)).toBe('ember');
  await second.close();
  expect(unexpectedRequests).toEqual([]);
});

for (const [id, name, mode] of newPalettes) {
  test(`${name} 可键盘选择、刷新保留并与另一标签页同步`, async ({ page, context, unexpectedRequests }) => {
    await page.goto('/home');
    const second = await context.newPage();
    await second.goto('/home');
    await page.getByRole('button', { name: '选择界面主题' }).click();
    const item = page.getByRole('menuitem').filter({ has: page.getByText(name, { exact: true }) });
    await item.focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('html')).toHaveAttribute('data-theme', id);
    await expect(second.locator('html')).toHaveAttribute('data-theme', id);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', id);
    expect(await page.locator('html').evaluate(root => root.style.colorScheme)).toBe(mode);
    await second.close();
    expect(unexpectedRequests).toEqual([]);
  });

  for (const width of [390, 768, 1280, 1440, 1920]) {
    test(`${name} 菜单布局与对比度 · ${width}px`, async ({ page, unexpectedRequests }, testInfo) => {
      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
      await page.addInitScript(({ key, id }) => localStorage.setItem(key, id), { key: storageKey, id });
      await page.goto('/home');
      await page.getByRole('button', { name: '选择界面主题' }).click();
      const menu = page.getByRole('menu').filter({ has: page.getByText('System 随行', { exact: true }) });
      await expect(menu.getByText('System 随行', { exact: true })).toBeVisible();
      const box = await menu.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width);
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
      const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(result.violations.map(v => ({ id: v.id, targets: v.nodes.map(n => n.target) }))).toEqual([]);
      await page.keyboard.press('Escape');
      await expect(menu).not.toBeVisible();
      await page.screenshot({ path: testInfo.outputPath('theme-home.png'), fullPage: true });
      expect(unexpectedRequests).toEqual([]);
    });
  }
}

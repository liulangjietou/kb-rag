import { test, expect } from './fixtures';
import { PERMISSIONS } from '../src/auth/permissions';

test.describe('授权页面快捷跳转', () => {
  test.use({ grantedPermissions: [PERMISSIONS.KB_READ] });
  test('快捷键搜索只包含授权页面，支持键盘打开和关闭', async ({ page, unexpectedRequests }) => {
    await page.goto('/home');
    await expect(page.getByRole('button', { name: '搜索页面与功能' })).toBeVisible();
    await page.keyboard.press('Control+k');
    const input = page.getByRole('textbox', { name: '页面名称' });
    await expect(input).toBeFocused();
    await input.fill('用户');
    await expect(page.getByText('没有匹配的可访问页面')).toBeVisible();
    await input.fill('知识库');
    await input.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/kb$/);
    const trigger = page.getByRole('button', { name: '搜索页面与功能' });
    await trigger.click();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(trigger).toBeFocused();
    expect(unexpectedRequests).toEqual([]);
  });
});

test('手机主导航限制焦点、Esc 关闭并恢复触发按钮', async ({ page, unexpectedRequests }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/home');
  const trigger = page.getByRole('button', { name: '打开主导航' });
  await trigger.click();
  await expect(page.getByRole('dialog', { name: '主导航' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(await page.evaluate(() => document.body.style.overflow)).not.toBe('hidden');
  expect(unexpectedRequests).toEqual([]);
});

test('八个主题可选，刷新和另一个标签页均同步', async ({ page, context, unexpectedRequests }) => {
  await page.goto('/home');
  const second = await context.newPage();
  await second.goto('/home');
  for (const [id, name] of [
    ['ocean', 'Ocean 深海'],
    ['violet', 'Violet 智紫'],
    ['cinder', 'Cinder 灰烬'],
    ['moss', 'Moss 苔原'],
    ['rose', 'Rose 绯樱'],
    ['graphite', 'Graphite 墨岩'],
    ['night', 'Night 夜航'],
    ['atlas', 'Atlas 翡翠'],
  ]) {
    await page.getByRole('button', { name: '选择界面主题' }).click();
    await page.getByRole('menuitem', { name: new RegExp(name) }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', id);
    await expect(second.locator('html')).toHaveAttribute('data-theme', id);
    expect(await page.evaluate(() => localStorage.getItem('kb-rag-web:theme-preset'))).toBe(id);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', id);
  }
  await second.close();
  expect(unexpectedRequests).toEqual([]);
});

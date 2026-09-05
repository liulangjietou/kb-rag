import { test, expect } from './fixtures';
import { PERMISSIONS } from '../src/auth/permissions';

test('首页在桌面宽度不产生页面横向溢出', async ({ page, unexpectedRequests }) => {
  await page.goto('/home');
  await expect(page.getByText('产品与交付知识库', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
  expect(unexpectedRequests).toEqual([]);
});

test.describe('知识库只读权限', () => {
  test.use({ grantedPermissions: [PERMISSIONS.KB_READ] });
  test('只读账号不能看到或触发写入与审核入口', async ({ page, unexpectedRequests }) => {
    await page.goto('/kb/kb_fixture');
    await expect(page.getByText('售后服务政策.docx', { exact: true })).toBeVisible();
    await expect(
      page.getByRole('button', { name: /^(通过|驳回|删除|重建|添加文档|导入聊天记录|索引配置)$/ }),
    ).toHaveCount(0);
    await expect(page.getByRole('switch', { name: '新文档需审核' })).toHaveCount(0);
    expect(unexpectedRequests).toEqual([]);
  });
});

test.describe('知识库写入与删除分权', () => {
  test.use({ grantedPermissions: [PERMISSIONS.KB_READ, PERMISSIONS.KB_WRITE] });
  test('有编辑权限而无删除权限时没有删除入口', async ({ page, unexpectedRequests }) => {
    await page.goto('/kb');
    await expect(page.getByText('产品与交付知识库', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '产品与交付知识库 更多操作' }).click();
    await expect(page.getByRole('menuitem', { name: /编辑/ })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: /删除/ })).toHaveCount(0);
    expect(unexpectedRequests).toEqual([]);
  });
});

test('配置页不产生未连接表单和重复初值警告', async ({ page, unexpectedRequests }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await page.goto('/apps/app_fixture');
  await expect(page.getByText('关联知识库', { exact: true })).toBeVisible();
  await expect.poll(() => errors.filter((text) => /useForm|initialValues/.test(text))).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

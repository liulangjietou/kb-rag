import { test, expect } from './fixtures';
import type { RoleSummary } from '../src/api/types';

const role = (suffix: string): RoleSummary => ({
  role_id: `role_${suffix}`, tenant_id: `tenant_${suffix}`, code: `EMPLOYEE_${suffix.toUpperCase()}`,
  name: `员工 ${suffix}`, description: '使用正式应用', builtin: false, kb_scope_all: true, kb_ids: [],
  permission_codes: ['app:use'], app_scope_all: false, app_ids: [`app_${suffix}`],
});
const catalogue = [{ code: 'app:use', name: '使用已发布的应用', module: 'APP', module_name: '应用' }];

test('角色应用范围加载失败可重试，保存失败保留名称、权限和范围', async ({ page, api, unexpectedRequests }) => {
  api['/roles'] = [role('a')];
  api['/roles/permissions'] = catalogue;
  let loads = 0;
  const bodies: unknown[] = [];
  await page.route('**/api/v1/roles/app-options?**', async (route) => {
    expect(new URL(route.request().url()).searchParams.get('role_id')).toBe('role_a');
    if (++loads === 1) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '暂不可用' } });
    return route.fulfill({ json: { code: 'OK', data: [{ app_id: 'app_a', name: '产品助手' }] } });
  });
  await page.route('**/api/v1/roles/role_a', async (route) => {
    bodies.push(route.request().postDataJSON());
    if (bodies.length === 1) return route.fulfill({ status: 409, json: { code: 'CONFLICT', message: '请刷新后重试' } });
    return route.fulfill({ json: { code: 'OK', data: role('a') } });
  });
  await page.goto('/roles');
  await page.getByRole('button', { name: '编辑权限' }).click();
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByText('应用列表加载失败，已保留当前授权选择')).toBeVisible();
  await expect(drawer.getByRole('button', { name: /^保\s*存$/ })).toBeDisabled();
  await drawer.getByRole('button', { name: '重试加载' }).click();
  await expect(drawer.getByText('产品助手', { exact: true })).toBeVisible();
  await drawer.getByRole('textbox', { name: /角色名称$/ }).fill('交付员工');
  await drawer.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(page.getByText('请刷新后重试')).toBeVisible();
  await expect(drawer.getByRole('textbox', { name: /角色名称$/ })).toHaveValue('交付员工');
  await expect(drawer.getByRole('checkbox', { name: '使用已发布的应用' })).toBeChecked();
  await drawer.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(drawer).toBeHidden();
  expect(bodies).toEqual(Array(2).fill({ name: '交付员工', description: '使用正式应用',
    kb_scope_all: true, kb_ids: [], permission_codes: ['app:use'], app_scope_all: false, app_ids: ['app_a'] }));
  expect(unexpectedRequests).toEqual([]);
});

test('快速切换角色后迟到的应用列表不能覆盖新租户', async ({ page, api, unexpectedRequests }) => {
  api['/roles'] = [role('a'), role('b')];
  api['/roles/permissions'] = catalogue;
  let releaseFirst!: () => void;
  const first = new Promise<void>((resolve) => { releaseFirst = resolve; });
  await page.route('**/api/v1/roles/app-options?**', async (route) => {
    const id = new URL(route.request().url()).searchParams.get('role_id');
    if (id === 'role_a') await first;
    await route.fulfill({ json: { code: 'OK', data: [{ app_id: id === 'role_a' ? 'app_a' : 'app_b',
      name: id === 'role_a' ? '旧租户助手' : '新租户助手' }] } });
  });
  await page.goto('/roles');
  await page.getByRole('row').filter({ hasText: '员工 a' }).getByRole('button', { name: '编辑权限' }).click();
  await page.getByRole('dialog').getByRole('button', { name: /^取\s*消$/ }).click();
  await page.getByRole('row').filter({ hasText: '员工 b' }).getByRole('button', { name: '编辑权限' }).click();
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByText('新租户助手', { exact: true })).toBeVisible();
  const response = page.waitForResponse((item) => item.url().includes('role_id=role_a'));
  releaseFirst();
  await response;
  await expect(drawer.getByText('新租户助手', { exact: true })).toBeVisible();
  await expect(drawer.getByText('旧租户助手', { exact: true })).toHaveCount(0);
  await expect(drawer.getByRole('textbox', { name: /角色名称$/ })).toHaveValue('员工 b');
  expect(unexpectedRequests).toEqual([]);
});

for (const all of [false, true]) {
  test(`390px 新角色默认无应用，可显式保存${all ? '全部' : '空'}范围`, async ({ page, api, unexpectedRequests }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    api['/roles/permissions'] = catalogue;
    api['/roles/app-options'] = [];
    const bodies: unknown[] = [];
    await page.route('**/api/v1/roles', async (route) => {
      if (route.request().method() === 'GET') return route.fallback();
      bodies.push(route.request().postDataJSON());
      return route.fulfill({ json: { code: 'OK', data: role('new') } });
    });
    await page.goto('/roles');
    await page.getByRole('button', { name: '新建角色' }).click();
    const drawer = page.getByRole('dialog');
    await drawer.getByRole('textbox', { name: /角色编码$/ }).fill('EMPLOYEE_NEW');
    await drawer.getByRole('textbox', { name: /角色名称$/ }).fill('新员工');
    await expect(drawer.getByRole('radio', { name: '指定应用（未选择时不可使用任何应用）' })).toBeChecked();
    if (all) await drawer.getByRole('radio', { name: '本租户全部应用（含此后新建的）' }).check();
    await drawer.getByText('使用已发布的应用', { exact: true }).click();
    await expect(drawer.getByRole('checkbox', { name: '使用已发布的应用' })).toBeChecked();
    await expect(drawer.getByRole('button', { name: /^保\s*存$/ })).toBeInViewport();
    await drawer.getByRole('button', { name: /^保\s*存$/ }).click();
    await expect(drawer).toBeHidden();
    expect(bodies).toEqual([{ code: 'EMPLOYEE_NEW', name: '新员工', kb_scope_all: true,
      kb_ids: [], permission_codes: ['app:use'], app_scope_all: all, app_ids: [] }]);
    expect(unexpectedRequests).toEqual([]);
  });
}

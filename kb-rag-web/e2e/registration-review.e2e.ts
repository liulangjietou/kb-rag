import { test, expect, pageData } from './fixtures';

test('注册审核切换租户后重新分配角色，开通失败可保留授权重试', async ({ page, api, unexpectedRequests }) => {
  const application = {
    application_id: 'REG-fixture', email: 'applicant@example.com', display_name: '申请用户',
    team_name: '知识管理组', application_note: '维护产品知识库', status: 'PENDING',
    email_verified_at: '2026-09-05T01:00:00Z', created_at: '2026-09-05T01:00:00Z',
  };
  api['/registration-reviews'] = pageData([application]);
  api['/tenants'] = ['a', 'b'].map((id) => ({ tenant_id: id, code: id, name: `租户 ${id}`, status: 'ENABLED', builtin: false, monthly_token_quota: 0, created_at: '' }));
  api['/roles'] = ['a', 'b'].map((id) => ({ role_id: `role_${id}`, tenant_id: id, code: `READER_${id}`, name: `读者 ${id}`, description: null, builtin: false, kb_scope_all: false, kb_ids: [], permission_codes: ['kb:read'] }));
  const requests: unknown[] = [];
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.route('**/api/v1/registration-reviews/REG-fixture/approve', async (route) => {
    requests.push(route.request().postDataJSON());
    if (requests.length === 1) return route.fulfill({ status: 503, json: { code: 'TEMPORARY_FAILURE', message: '请稍后重试' } });
    api['/registration-reviews'] = pageData([]);
    await route.fulfill({ json: { code: 'OK', data: { ...application, status: 'APPROVED', tenant_id: 'b', role_ids: ['role_b'], reviewed_at: '2026-09-05T02:00:00Z' } } });
  });
  await page.goto('/users/registration-reviews');
  await page.getByRole('button', { name: /^审\s*核$/ }).click();
  const drawer = page.getByRole('dialog', { name: '审核与角色开通' });
  const choose = async (label: string, text: string) => {
    await drawer.locator('.ant-select').filter({ has: page.getByRole('combobox', { name: label }) }).click();
    await page.locator('.ant-select-item-option').filter({ hasText: text }).click();
  };
  await choose('所属租户', '租户 a（a）');
  await choose('分配角色', '读者 a（READER_a）');
  await choose('所属租户', '租户 b（b）');
  await drawer.getByRole('button', { name: '通过并开通账号' }).click();
  await expect(drawer.getByText('请至少选择一个角色')).toBeVisible();
  expect(requests).toEqual([]);
  await choose('分配角色', '读者 b（READER_b）');
  await drawer.getByRole('button', { name: '通过并开通账号' }).click();
  await expect(drawer.getByText(/账号开通失败/)).toBeVisible();
  await drawer.getByRole('button', { name: '通过并开通账号' }).click();
  await expect(drawer).toBeHidden();
  await expect(page.getByText('没有符合条件的注册申请')).toBeVisible();
  expect(requests).toEqual(Array(2).fill({ tenant_id: 'b', role_ids: ['role_b'] }));
  expect(pageErrors).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

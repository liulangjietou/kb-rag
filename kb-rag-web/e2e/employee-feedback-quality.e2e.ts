import { THEME_IDS } from './theme-fixture';
import { test, expect, pageData } from './fixtures';
import { fixture } from './quality-issues-fixture';
import AxeBuilder from '@axe-core/playwright';
import { PERMISSIONS } from '../src/auth/permissions';

test('员工负面回答进入人工质量处理，原回答不会直接成为标准答案', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  api['/kb/kb_fixture/employee-feedback'] = pageData([{ run_id: 'run_employee', question: '为什么写工具需要幂等？',
    feedback_note: '回答把第三条原则说成第四条', feedback_updated_at: '2026-09-14T12:00:00', content_restricted: false }]);
  api['/kb/kb_fixture/employee-feedback/run_employee'] = { run_id: 'run_employee', question: '为什么写工具需要幂等？',
    answer: '原错误回答：第四条是幂等性[1]', feedback_verdict: 'BAD', feedback_note: '回答把第三条原则说成第四条',
    feedback_updated_at: '2026-09-14T12:00:00', app_version: 'v1.3', citations: [{ file_name: '生产指南', content: '第三，幂等性。第四，结构化返回。', inherited: false }] };
  await page.goto('/kb/kb_fixture');
  await page.getByRole('tab', { name: '质量与反馈', exact: true }).click();
  await page.getByRole('tab', { name: '反馈管理', exact: true }).click();
  await page.getByRole('tab', { name: '员工问答反馈', exact: true }).click();
  await expect(page.getByText('为什么写工具需要幂等？', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '查看原回答', exact: true }).click();
  const source = page.getByRole('dialog', { name: '员工问答反馈', exact: true });
  await expect(source.getByText('原错误回答：第四条是幂等性[1]', { exact: true })).toBeVisible();
  await expect(source.getByText('第三，幂等性。第四，结构化返回。', { exact: true })).toBeVisible();
  await source.getByRole('button', { name: '处理问题', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '处理质量问题' })).toBeVisible();
  expect(state.writes).toEqual([{ action: 'create', body: { source_type: 'EMPLOYEE_ANSWER', source_id: 'run_employee' } }]);
  expect(state.issue.case_id).toBeNull();
  expect(unexpectedRequests).toEqual([]);
});

async function openFeedback(page: import('@playwright/test').Page, api: Record<string, unknown>) {
  await fixture(page, api);
  api['/kb/kb_fixture/employee-feedback'] = pageData([{ run_id: 'source', question: '待核对的员工问题', feedback_note: '员工说明', content_restricted: false }]);
  api['/kb/kb_fixture/employee-feedback/source'] = { run_id: 'source', question: '待核对的员工问题', answer: '可能存在错误的回答，请结合来源核对。'.repeat(10),
    feedback_verdict: 'BAD', feedback_note: '员工说明', app_version: 'v1', citations: [
      { file_name: '长文件名称与跨页资料'.repeat(8), content: '当前正确证据原文。'.repeat(15), inherited: false },
      { file_name: '之前对话引用', content: '历史依赖同样要求当前权限', inherited: true },
    ] };
  await page.goto('/kb/kb_fixture');
  await page.getByRole('tab', { name: '质量与反馈', exact: true }).click();
  await page.getByRole('tab', { name: '反馈管理', exact: true }).click();
  await page.getByRole('tab', { name: '员工问答反馈', exact: true }).click();
}

test('读取失败不能显示成没有反馈', async ({ page, api, unexpectedRequests }) => {
  await page.route('**/api/v1/kb/kb_fixture/employee-feedback?*', route => route.fulfill({ status: 500, json: { code: 'INTERNAL_ERROR', message: '暂时不可用' } }));
  await openFeedback(page, api);
  await expect(page.getByText('员工反馈读取失败，请刷新重试。')).toBeVisible();
  await expect(page.getByText('暂无员工负面反馈')).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('返回页面发现资料撤权时清除原回答和引用', async ({ page, api, unexpectedRequests }) => {
  await openFeedback(page, api);
  await page.getByRole('button', { name: '查看原回答', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '员工问答反馈', exact: true });
  await expect(dialog.getByText('历史依赖同样要求当前权限')).toBeVisible();
  await page.route('**/api/v1/kb/kb_fixture/employee-feedback/source', route => route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: '资料撤权' } }));
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(dialog.getByText('该反馈或依赖资料已不可访问，原回答已隐藏。')).toBeVisible();
  await expect(dialog.getByText('历史依赖同样要求当前权限')).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: '处理问题' })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test.describe('缺少应用读取权限', () => {
  test.use({ grantedPermissions: [PERMISSIONS.KB_READ, PERMISSIONS.FEEDBACK_MANAGE] });
  test('保留检索反馈入口并隐藏员工问答反馈', async ({ page, api, unexpectedRequests }) => {
    await fixture(page, api);
    await page.goto('/kb/kb_fixture');
    await page.getByRole('tab', { name: '质量与反馈', exact: true }).click();
    await page.getByRole('tab', { name: '反馈管理', exact: true }).click();
    await expect(page.getByRole('tab', { name: '检索反馈', exact: true })).toBeVisible();
    await expect(page.getByRole('tab', { name: '员工问答反馈', exact: true })).toHaveCount(0);
    expect(unexpectedRequests).toEqual([]);
  });
});

for (const theme of THEME_IDS) {
  test(`员工反馈长内容与引用 · ${theme} · 390px`, async ({ page, api, unexpectedRequests }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.addInitScript(preset => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
    await openFeedback(page, api);
    await page.getByRole('button', { name: '查看原回答', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '员工问答反馈', exact: true });
    await expect(dialog.getByText('历史依赖同样要求当前权限')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    expect(await dialog.evaluate(el => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(0);
    const audit = await new AxeBuilder({ page }).include('.quality-issue-drawer').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect(audit.violations.map(v => ({ id: v.id, targets: v.nodes.map(n => n.target) }))).toEqual([]);
    await dialog.getByRole('button', { name: '刷新原回答', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    await expect(page.getByRole('button', { name: '查看原回答', exact: true })).toBeFocused();
    expect(unexpectedRequests).toEqual([]);
  });
}

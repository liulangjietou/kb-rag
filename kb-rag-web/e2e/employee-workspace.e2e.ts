import { test, expect } from './fixtures';
import { employeeConversation, employeeFixture, employeeRun, employeeUrl } from './employee-workspace-fixture';

test.describe('员工知识问答', () => {
  test.use({ grantedPermissions: ['app:use'] });

  test('只持使用权限可进入正式问答，首次读取后聚焦输入，输入法与换行不会误发', async ({ page, unexpectedRequests }) => {
    const state = await employeeFixture(page, { runs: [], conversation: { ...employeeConversation, last_turn: 0 } });
    await page.goto(employeeUrl);
    const input = page.getByRole('textbox', { name: '输入知识问题' });
    await expect(input).toBeEnabled();
    await expect(input).toBeFocused();
    await input.fill('退货申请');
    await input.dispatchEvent('compositionstart');
    await input.press('Enter');
    await input.dispatchEvent('compositionend');
    expect(state.submissions).toHaveLength(0);
    await input.press('Shift+Enter');
    expect(state.submissions).toHaveLength(0);
    await input.press('Enter');
    await expect(page.getByText('已保存', { exact: true })).toBeVisible();
    expect(state.submissions).toHaveLength(1);
    await expect(input).toHaveValue('');
    await expect(page.getByRole('table')).toBeVisible();
    await expect(page.getByRole('button', { name: '知识库', exact: true })).toHaveCount(0);
    expect(unexpectedRequests).toEqual([]);
  });

  test('重连和刷新只读取同一运行，明确停止后保留部分正文', async ({ page, unexpectedRequests }) => {
    const active = { ...employeeRun, status: 'RUNNING' as const, stage: 'GENERATING' as const, answer: '已保存的部分正文' };
    const state = await employeeFixture(page, { runs: [active], conversation: { ...employeeConversation, active_run_id: active.run_id } });
    state.streamComplete = false;
    await page.goto(employeeUrl);
    await expect(page.getByText('连接中断，正在恢复同一回答')).toBeVisible();
    await expect.poll(() => state.streams).toBeGreaterThanOrEqual(2);
    expect(state.submissions).toHaveLength(0);
    await page.reload();
    await expect(page.getByText('已保存的部分正文')).toBeVisible();
    expect(state.stops).toHaveLength(0);
    await page.getByRole('button', { name: /停止回答$/ }).click();
    await expect(page.getByText('已停止', { exact: true })).toBeVisible();
    await expect(page.getByText('已保存的部分正文')).toBeVisible();
    expect(state.stops).toEqual([active.run_id]);
    expect(state.submissions).toHaveLength(0);
    expect(unexpectedRequests).toEqual([]);
  });

  test('发送结果不明时保留草稿，显式确认沿用原请求，重新生成使用新请求', async ({ page, unexpectedRequests }) => {
    const state = await employeeFixture(page, { runs: [], conversation: { ...employeeConversation, last_turn: 0 } });
    state.submitUnavailable = true;
    await page.goto(employeeUrl);
    const input = page.getByRole('textbox', { name: '输入知识问题' });
    await input.fill('申请售后需要准备什么？');
    await page.getByRole('button', { name: /发送$/ }).click();
    await expect(page.getByRole('button', { name: '确认发送结果' })).toBeVisible();
    await expect(input).toHaveValue('申请售后需要准备什么？');
    await expect(input).toBeDisabled();
    state.submitUnavailable = false;
    await page.getByRole('button', { name: '确认发送结果' }).click();
    await expect(page.getByText('已保存', { exact: true })).toBeVisible();
    expect(state.submissions[1]).toEqual(state.submissions[0]);
    await page.getByRole('button', { name: '重新生成', exact: true }).click();
    await expect(page.getByText('已保存', { exact: true })).toHaveCount(2);
    expect(state.submissions[2].request_id).not.toBe(state.submissions[0].request_id);
    expect(unexpectedRequests).toEqual([]);
  });

  test('引用区分当前和上下文来源，打开前撤权会隐藏整条答案', async ({ page, unexpectedRequests }) => {
    const state = await employeeFixture(page);
    await page.goto(employeeUrl);
    await page.getByRole('button', { name: '查看引用 1', exact: true }).click();
    const reader = page.getByRole('dialog');
    await expect(reader.getByRole('heading', { name: '本次引用片段' })).toBeVisible();
    await expect(reader.getByText('片段 3 · 版本 v3')).toBeVisible();
    await expect(reader.getByText('第 3 页', { exact: false })).toHaveCount(0);
    await reader.getByRole('button', { name: '关闭', exact: true }).click();
    await expect(reader).toHaveCount(0);
    state.revokeOnRead = true;
    const reads = state.historyReads;
    await page.getByRole('button', { name: '查看引用 1', exact: true }).click();
    await expect.poll(() => state.historyReads).toBeGreaterThan(reads);
    await expect(page.getByRole('dialog').getByText('这条引用当前已不可访问')).toBeVisible();
    await expect(page.getByRole('table')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '复制回答', exact: true })).toHaveCount(0);
    expect(unexpectedRequests).toEqual([]);
  });

  test('重命名失败保留输入，重试成功后更新历史，删除有明确确认', async ({ page, unexpectedRequests }) => {
    const state = await employeeFixture(page);
    state.renameFails = true;
    await page.goto(employeeUrl);
    await page.getByRole('button', { name: '重命名会话' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('textbox', { name: '会话标题' }).fill('退货核验工作指引');
    await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
    await expect(dialog.getByText('标题保存失败，请重试')).toBeVisible();
    await expect(dialog.getByRole('textbox', { name: '会话标题' })).toHaveValue('退货核验工作指引');
    state.renameFails = false;
    await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
    await expect(page.getByRole('heading', { name: '退货核验工作指引' })).toBeVisible();
    await page.getByRole('button', { name: '删除会话', exact: true }).click();
    await expect(page.getByRole('dialog').getByText('删除后会话将从历史中移除，正在执行的回答也会停止。')).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: '保留会话' }).click();
    expect(state.deleted).toBe(false);
    await page.getByRole('button', { name: '删除会话', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '删除会话', exact: true }).click();
    await expect(page).toHaveURL(/app=app_fixture$/);
    expect(state.deleted).toBe(true);
    expect(unexpectedRequests).toEqual([]);
  });
});

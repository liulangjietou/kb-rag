import { test, expect } from './fixtures';
import { employeeConversation, employeeFixture, employeeRun, employeeUrl } from './employee-workspace-fixture';

test.describe('员工会话阅读与恢复', () => {
  test.use({ grantedPermissions: ['app:use'] });

  test('两个标签同时提问时，冲突标签读取已有运行并保留未发送草稿', async ({ page, context, unexpectedRequests }) => {
    const state = await employeeFixture(context, { runs: [], conversation: { ...employeeConversation, last_turn: 0 } });
    state.streamComplete = false;
    await page.goto(employeeUrl);
    const second = await context.newPage();
    await second.goto(employeeUrl);
    await expect(second.getByRole('textbox', { name: '输入知识问题' })).toBeEnabled();
    // 两个提交都到达后才放行首个请求，避免切换标签触发的正常刷新先替换发送按钮。
    let secondSubmitted!: () => void;
    const bothSubmitted = new Promise<void>((resolve) => { secondSubmitted = resolve; });
    const submitUrl = '/api/v1/workspace/apps/app_fixture/conversations/conv_fixture/runs';
    const firstFinished = page.waitForResponse((response) => new URL(response.url()).pathname === submitUrl
      && response.request().method() === 'POST' && response.status() === 200);
    const submitPath = `**${submitUrl}`;
    await page.route(submitPath, async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await bothSubmitted;
      await route.fallback();
    });
    await second.route(submitPath, async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      secondSubmitted();
      await firstFinished;
      await route.fallback();
    });
    await page.getByRole('textbox', { name: '输入知识问题' }).fill('第一条问题');
    await second.getByRole('textbox', { name: '输入知识问题' }).fill('第二条问题');
    await Promise.all([
      page.getByRole('button', { name: /发送$/ }).click(),
      second.getByRole('button', { name: /发送$/ }).click(),
    ]);
    await expect(page.getByRole('button', { name: /停止回答$/ })).toBeVisible();
    await expect(second.getByText('上一条回答仍在执行，请等待或停止后继续')).toBeVisible();
    await expect(second.getByRole('textbox', { name: '输入知识问题' })).toHaveValue('第二条问题');
    await expect(second.getByRole('button', { name: /停止回答$/ })).toBeVisible();
    expect(state.runs).toHaveLength(1);
    await second.getByRole('button', { name: /停止回答$/ }).click();
    await expect(second.getByText('已停止', { exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByText('已停止', { exact: true })).toBeVisible();
    expect(state.stops).toHaveLength(1);
    expect(state.submissions).toHaveLength(2);
    expect(unexpectedRequests).toEqual([]);
  });

  test('阅读长答案时不抢滚动位置，新内容有明确返回入口', async ({ page, unexpectedRequests }) => {
    const answer = Array.from({ length: 35 }, (_, i) => `第 ${i + 1} 条说明：核验业务背景，再按资料中的步骤处理。`).join('\n\n');
    const active = { ...employeeRun, answer, status: 'RUNNING' as const, stage: 'GENERATING' as const };
    const state = await employeeFixture(page, { runs: [active], conversation: { ...employeeConversation, active_run_id: active.run_id } });
    let complete!: () => void;
    state.onEvents = async (route) => {
      await new Promise<void>((resolve) => { complete = resolve; });
      const final = { ...active, answer: `${answer}\n\n新增的最后一条结论。`, status: 'SUCCEEDED', stage: 'FINISHED', revision: 8 };
      await route.fulfill({ contentType: 'text/event-stream', body: `event: snapshot\ndata: ${JSON.stringify(final)}\n\n` });
    };
    await page.goto(employeeUrl);
    await expect.poll(() => state.streams).toBe(1);
    const scroll = page.locator('.conversation-scroll');
    await expect.poll(() => scroll.evaluate((element) => element.scrollTop)).toBeGreaterThan(500);
    await page.getByRole('textbox', { name: '输入知识问题' }).fill('补充背景\n订单已签收\n资料待确认\n客户要求退货\n准备下一条问题');
    await scroll.evaluate((element) => { element.scrollTop = 0; element.dispatchEvent(new Event('scroll')); });
    complete();
    await expect(page.getByText('已保存', { exact: true })).toBeAttached();
    expect(await scroll.evaluate((element) => element.scrollTop)).toBe(0);
    const latestButton = page.getByRole('button', { name: /回到最新回答/ });
    const latestBox = await latestButton.boundingBox();
    const composer = await page.locator('.conversation-composer').boundingBox();
    expect(latestBox!.y + latestBox!.height, '新内容入口不能覆盖多行输入区').toBeLessThanOrEqual(composer!.y);
    await latestButton.click();
    await expect(page.getByText('新增的最后一条结论。')).toBeInViewport();
    expect(unexpectedRequests).toEqual([]);
  });

  test('更早记录使用独占轮次游标，返回最近页后才可继续输入', async ({ page, unexpectedRequests }) => {
    const runs = Array.from({ length: 21 }, (_, i) => ({ ...employeeRun, run_id: `run_${i + 1}`, turn_no: i + 1,
      question: `问题 ${i + 1}`, answer: `回答 ${i + 1}`, references: [] }));
    const state = await employeeFixture(page, { runs, conversation: { ...employeeConversation, last_turn: 21 } });
    await page.goto(employeeUrl);
    await expect(page.getByRole('article', { name: '第 21 轮问答', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '更早记录', exact: true }).click();
    await expect(page.getByRole('article', { name: '第 1 轮问答', exact: true })).toBeVisible();
    await expect(page.getByRole('article', { name: '第 2 轮问答', exact: true })).toHaveCount(0);
    expect(state.historyBefore).toContain(2);
    await expect(page.getByRole('textbox', { name: '输入知识问题' })).toHaveCount(0);
    await page.getByRole('button', { name: '返回最近问答后继续提问', exact: true }).click();
    await expect(page.getByRole('article', { name: '第 21 轮问答', exact: true })).toBeVisible();
    await expect(page.getByRole('textbox', { name: '输入知识问题' })).toBeEnabled();
    expect(unexpectedRequests).toEqual([]);
  });

  test('离开页面只关闭订阅，再次打开保留同一运行和已保存内容', async ({ page, unexpectedRequests }) => {
    const active = { ...employeeRun, status: 'RUNNING' as const, stage: 'GENERATING' as const, answer: '离开前保存的内容' };
    const state = await employeeFixture(page, { runs: [active], conversation: { ...employeeConversation, active_run_id: active.run_id } });
    state.streamComplete = false;
    await page.goto(employeeUrl);
    await expect(page.getByText('离开前保存的内容')).toBeVisible();
    const streams = state.streams;
    await page.getByRole('menuitem', { name: /工作概览$/ }).click();
    await expect(page).toHaveURL(/\/home$/);
    expect(state.stops).toEqual([]);
    await page.goto(employeeUrl);
    await expect(page.getByText('离开前保存的内容')).toBeVisible();
    await expect.poll(() => state.streams).toBeGreaterThan(streams);
    expect(state.submissions).toEqual([]);
    expect(unexpectedRequests).toEqual([]);
  });
});

test.describe('员工入口权限', () => {
  test.use({ grantedPermissions: [] });
  test('没有使用权限时路由拒绝并且不请求员工内容', async ({ page, unexpectedRequests }) => {
    let contentRequests = 0;
    page.on('request', (request) => { if (request.url().includes('/api/v1/workspace/')) contentRequests++; });
    await page.goto(employeeUrl);
    await expect(page.getByText('当前账号没有访问该页面的权限，如需开通请联系管理员。')).toBeVisible();
    expect(contentRequests).toBe(0);
    expect(unexpectedRequests).toEqual([]);
  });
});

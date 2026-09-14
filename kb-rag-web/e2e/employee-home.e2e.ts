import { test, expect, app } from './fixtures';
import { employeeConversation, employeeFixture, employeeUrl } from './employee-workspace-fixture';

test.use({ grantedPermissions: ['app:use'] });

test('最小员工从首页继续自己的会话，进入后只读取原运行', async ({ page, api, unexpectedRequests }) => {
  api['/workspace/overview'] = { applications: [app], recent_conversations: [
    { ...employeeConversation, app_name: app.name },
  ] };
  const state = await employeeFixture(page);
  await page.goto('/home');
  await expect(page.getByRole('heading', { name: '知识问答', exact: true })).toBeVisible();
  await expect(page.getByText('暂无可用功能，请联系管理员分配角色')).toHaveCount(0);
  await page.getByRole('button', { name: /售后政策与处理流程/ }).click();
  await expect(page).toHaveURL(employeeUrl);
  await expect(page.getByRole('table')).toBeVisible();
  expect(state.submissions).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

test('首页读取失败显示可重试错误，恢复后可打开正式应用且不创建会话', async ({ page, api, unexpectedRequests }) => {
  api['/workspace/overview'] = { applications: [app], recent_conversations: [] };
  const state = await employeeFixture(page);
  let unavailable = true;
  await page.route('**/api/v1/workspace/overview', (route) => unavailable
    ? route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '服务暂不可用' } }) : route.fallback());
  await page.goto('/home');
  await expect(page.getByText('问答入口加载失败')).toBeVisible();
  await expect(page.getByText('还没有会话，从上方选择应用开始提问。')).toHaveCount(0);
  unavailable = false;
  await page.getByRole('button', { name: /^重\s*试$/ }).click();
  await expect(page.getByText('还没有会话，从上方选择应用开始提问。')).toBeVisible();
  await page.getByRole('button', { name: /客户服务助手.*v1.3/ }).click();
  await expect(page).toHaveURL('/workspace?app=app_fixture');
  expect(state.created).toBe(0);
  expect(state.submissions).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

test('刷新授权后清除已撤回的应用与会话，不保留旧标题', async ({ page, api, unexpectedRequests }) => {
  api['/workspace/overview'] = { applications: [app], recent_conversations: [{ ...employeeConversation, app_name: app.name }] };
  await page.goto('/home');
  await expect(page.getByRole('button', { name: /售后政策与处理流程/ })).toBeVisible();
  api['/workspace/overview'] = { applications: [], recent_conversations: [] };
  await page.getByRole('button', { name: '刷新知识问答入口' }).click();
  await expect(page.getByText('还没有可使用的正式应用，请联系管理员确认应用授权与发布状态。')).toBeVisible();
  await expect(page.getByText('售后政策与处理流程')).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('首页会话过期后回到登录，不将未授权结果展示为零条', async ({ page, unexpectedRequests }) => {
  await page.route('**/api/v1/workspace/overview', (route) => route.fulfill({
    status: 401, json: { code: 'UNAUTHORIZED', message: '登录已过期' },
  }));
  await page.goto('/home');
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => window.history.state?.usr?.from?.pathname)).toBe('/home');
  expect(unexpectedRequests).toEqual([]);
});

test('重新聚焦首页时先清除旧摘要，再展示当前授权结果', async ({ page, api, unexpectedRequests }) => {
  api['/workspace/overview'] = { applications: [app], recent_conversations: [{ ...employeeConversation, app_name: app.name }] };
  await page.goto('/home');
  await expect(page.getByText('售后政策与处理流程')).toBeVisible();
  let release: () => void = () => {};
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/v1/workspace/overview', async (route) => {
    await pending;
    await route.fulfill({ json: { code: 'OK', data: { applications: [], recent_conversations: [] } } });
  });
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByRole('status', { name: '正在读取问答入口' })).toBeVisible();
  await expect(page.getByText('售后政策与处理流程')).toHaveCount(0);
  release();
  await expect(page.getByText('还没有可使用的正式应用，请联系管理员确认应用授权与发布状态。')).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test.describe('独立应用使用权限', () => {
  test.use({ grantedPermissions: ['app:read'] });

  test('只有管理权限的首页不请求员工目录或个人会话', async ({ page, unexpectedRequests }) => {
    const employeeRequests: string[] = [];
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.startsWith('/api/v1/workspace/')) employeeRequests.push(request.url());
    });
    await page.goto('/home');
    await expect(page.getByRole('heading', { name: '最近访问' })).toBeVisible();
    await expect(page.getByRole('region', { name: '知识问答' })).toHaveCount(0);
    await expect(page.getByText('进入知识问答')).toHaveCount(0);
    expect(employeeRequests).toEqual([]);
    expect(unexpectedRequests).toEqual([]);
  });
});

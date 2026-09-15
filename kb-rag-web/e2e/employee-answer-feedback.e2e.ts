import { test, expect } from './fixtures';
import { employeeFixture, employeeUrl } from './employee-workspace-fixture';

test.use({ grantedPermissions: ['app:use'] });

test('成功回答可以填写反馈，保存后刷新仍显示原评价且不重新生成', async ({ page, unexpectedRequests }) => {
  const state = await employeeFixture(page);
  const writes: unknown[] = [];
  await page.route('**/runs/run_fixture/feedback', async (route) => {
    expect(route.request().method()).toBe('PUT');
    const body = route.request().postDataJSON();
    writes.push(body);
    state.runs[0] = { ...state.runs[0], revision: 6,
      feedback: { verdict: body.verdict, note: body.note, updated_at: '2026-09-11T10:10:00' } };
    await route.fulfill({ json: { code: 'OK', data: state.runs[0] } });
  });
  await page.goto(employeeUrl);
  await page.getByRole('button', { name: '回答需改进' }).click();
  const dialog = page.getByRole('dialog', { name: '回答反馈' });
  await dialog.getByRole('textbox', { name: '问题说明' }).fill('请说明退货期限对应的依据');
  await dialog.getByRole('button', { name: '保存反馈' }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: '回答需改进' })).toHaveAttribute('aria-pressed', 'true');
  await page.reload();
  await expect(page.getByRole('button', { name: '回答需改进' })).toHaveAttribute('aria-pressed', 'true');
  expect(writes).toEqual([{ verdict: 'BAD', note: '请说明退货期限对应的依据', expected_revision: 5 }]);
  expect(state.submissions).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

test('切换标签页后仍保留尚未提交的反馈说明', async ({ page, unexpectedRequests }) => {
  await employeeFixture(page);
  await page.goto(employeeUrl);
  await page.getByRole('button', { name: '回答需改进' }).click();
  const dialog = page.getByRole('dialog', { name: '回答反馈' });
  await dialog.getByRole('textbox', { name: '问题说明' }).fill('尚未提交，正在核对来源');
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(dialog).not.toBeVisible();
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('textbox', { name: '问题说明' })).toHaveValue('尚未提交，正在核对来源');
  await dialog.getByRole('textbox', { name: '问题说明' }).click();
  await expect(dialog.getByRole('textbox', { name: '问题说明' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: '回答需改进' })).toBeFocused();
  expect(unexpectedRequests).toEqual([]);
});

test('保存失败保留说明，重试成功之前不显示已评价', async ({ page, unexpectedRequests }) => {
  const state = await employeeFixture(page);
  let attempts = 0;
  await page.route('**/runs/run_fixture/feedback', async (route) => {
    attempts++;
    if (attempts === 1) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '反馈暂时无法保存' } });
    const body = route.request().postDataJSON();
    state.runs[0] = { ...state.runs[0], revision: 6, feedback: { verdict: 'BAD', note: body.note, updated_at: '2026-09-11T10:10:00' } };
    await route.fulfill({ json: { code: 'OK', data: state.runs[0] } });
  });
  await page.goto(employeeUrl);
  await page.getByRole('button', { name: '回答需改进' }).click();
  const dialog = page.getByRole('dialog', { name: '回答反馈' });
  await dialog.getByRole('textbox', { name: '问题说明' }).fill('重试时保留这段说明');
  await dialog.getByRole('button', { name: '保存反馈' }).click();
  await expect(dialog.getByText('反馈暂时无法保存')).toBeVisible();
  await expect(dialog.getByRole('textbox', { name: '问题说明' })).toHaveValue('重试时保留这段说明');
  await expect(page.getByRole('button', { name: '回答需改进' })).toHaveAttribute('aria-pressed', 'false');
  await dialog.getByRole('button', { name: '保存反馈' }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: '回答需改进' })).toHaveAttribute('aria-pressed', 'true');
  expect(attempts).toBe(2);
  expect(unexpectedRequests).toEqual([]);
});

test('其他页面更新评价后，先核对最新反馈才能重新保存', async ({ page, unexpectedRequests }) => {
  const state = await employeeFixture(page);
  const revisions: number[] = [];
  await page.route('**/runs/run_fixture/feedback', async (route) => {
    const body = route.request().postDataJSON();
    revisions.push(body.expected_revision);
    if (revisions.length === 1) {
      state.runs[0] = { ...state.runs[0], revision: 6, feedback: { verdict: 'GOOD', note: '另一页面的反馈', updated_at: '2026-09-11T10:10:00' } };
      return route.fulfill({ status: 409, json: { code: 'FEEDBACK_VERSION_CONFLICT', message: '反馈已更新，请先核对' } });
    }
    state.runs[0] = { ...state.runs[0], revision: 7, feedback: { verdict: body.verdict, note: body.note, updated_at: '2026-09-11T10:20:00' } };
    await route.fulfill({ json: { code: 'OK', data: state.runs[0] } });
  });
  await page.goto(employeeUrl);
  await page.getByRole('button', { name: '回答需改进' }).click();
  const dialog = page.getByRole('dialog', { name: '回答反馈' });
  await dialog.getByRole('textbox', { name: '问题说明' }).fill('本页尚未提交的说明');
  await dialog.getByRole('button', { name: '保存反馈' }).click();
  await expect(dialog.getByRole('button', { name: '保存反馈' })).toBeDisabled();
  await expect(dialog.getByRole('textbox', { name: '问题说明' })).toHaveValue('本页尚未提交的说明');
  await dialog.getByRole('button', { name: '载入最新反馈' }).click();
  await expect(dialog.getByRole('textbox', { name: '问题说明' })).toHaveValue('另一页面的反馈');
  await dialog.getByRole('textbox', { name: '问题说明' }).fill('重新核对后的说明');
  await dialog.getByRole('button', { name: '保存反馈' }).click();
  await expect(dialog).not.toBeVisible();
  expect(revisions).toEqual([5, 6]);
  expect(state.runs[0].feedback?.note).toBe('重新核对后的说明');
  expect(unexpectedRequests).toEqual([]);
});

test('权限撤回清除已保存反馈与当前草稿，并关闭反馈入口', async ({ page, unexpectedRequests }) => {
  const state = await employeeFixture(page);
  state.runs[0].feedback = { verdict: 'BAD', note: '来自原答案的受限说明', updated_at: '2026-09-11T10:10:00' };
  await page.goto(employeeUrl);
  await page.getByRole('button', { name: '回答需改进' }).click();
  const dialog = page.getByRole('dialog', { name: '回答反馈' });
  await dialog.getByRole('textbox', { name: '问题说明' }).fill('尚未提交的受限草稿');
  state.revokeOnRead = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: '回答需改进' })).toHaveCount(0);
  await expect(page.getByText('来自原答案的受限说明', { exact: true })).toHaveCount(0);
  await expect(page.getByText('尚未提交的受限草稿', { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('有帮助只提交一次，收到保存确认后才选中', async ({ page, unexpectedRequests }) => {
  const state = await employeeFixture(page);
  let writes = 0;
  let release: () => void = () => {};
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/runs/run_fixture/feedback', async (route) => {
    writes++;
    expect(route.request().postDataJSON()).toEqual({ verdict: 'GOOD', expected_revision: 5 });
    await pending;
    state.runs[0] = { ...state.runs[0], revision: 6, feedback: { verdict: 'GOOD', note: null, updated_at: '2026-09-11T10:10:00' } };
    await route.fulfill({ json: { code: 'OK', data: state.runs[0] } });
  });
  await page.goto(employeeUrl);
  const helpful = page.getByRole('button', { name: '回答有帮助' });
  await helpful.click();
  await expect(helpful).toBeDisabled();
  await expect(helpful).toHaveAttribute('aria-pressed', 'false');
  release();
  await expect(helpful).toHaveAttribute('aria-pressed', 'true');
  await helpful.click();
  expect(writes).toBe(1);
  expect(state.submissions).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

test('已保存但响应丢失时，重试保留同一修订号与说明', async ({ page, unexpectedRequests }) => {
  const state = await employeeFixture(page);
  const writes: unknown[] = [];
  await page.route('**/runs/run_fixture/feedback', async (route) => {
    const body = route.request().postDataJSON();
    writes.push(body);
    if (writes.length === 1) {
      state.runs[0] = { ...state.runs[0], revision: 6, feedback: { verdict: 'BAD', note: body.note, updated_at: '2026-09-11T10:10:00' } };
      return route.abort('failed');
    }
    await route.fulfill({ json: { code: 'OK', data: state.runs[0] } });
  });
  await page.goto(employeeUrl);
  await page.getByRole('button', { name: '回答需改进' }).click();
  const dialog = page.getByRole('dialog', { name: '回答反馈' });
  await dialog.getByRole('textbox', { name: '问题说明' }).fill('保存结果待确认的原说明');
  await dialog.getByRole('button', { name: '保存反馈' }).click();
  await expect(dialog.getByText('反馈保存结果尚未确认，请重试保存')).toBeVisible();
  await dialog.getByRole('button', { name: '保存反馈' }).click();
  await expect(dialog).not.toBeVisible();
  expect(writes).toHaveLength(2);
  expect(writes[0]).toEqual(writes[1]);
  expect(writes[0]).toEqual({ verdict: 'BAD', note: '保存结果待确认的原说明', expected_revision: 5 });
  expect(state.submissions).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

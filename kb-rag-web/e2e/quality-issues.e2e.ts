import { test, expect, pageData, documents } from './fixtures';
import { fixture, openIssue, choose, fillCorrection, standard, root, reference } from './quality-issues-fixture';

test('负面反馈经过领取、人工纠正和真实结果确认后才显示已解决', async ({ page, api, unexpectedRequests }) => {
  const warnings: string[] = [];
  page.on('console', (event) => { if (event.type() === 'error') warnings.push(event.text()); });
  const state = await fixture(page, api);
  await page.goto('/kb/kb_fixture');
  await page.getByRole('tab', { name: '质量与反馈', exact: true }).click();
  await page.getByRole('tab', { name: '反馈管理', exact: true }).click();
  await page.getByRole('button', { name: '处理问题', exact: true }).click();
  await page.getByRole('button', { name: '领取问题', exact: true }).click();
  await fillCorrection(page);
  await page.getByRole('button', { name: '保存纠正用例', exact: true }).click();
  await expect(page.getByRole('heading', { name: '核对实际回归结果' })).toBeVisible();
  await page.getByRole('combobox', { name: '已完成的答案评测', exact: true }).click();
  await page.locator('.ant-select-dropdown:visible').getByText(/纠正后回归/).click();
  await expect(page.getByText(standard, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '确认回归并解决' })).toBeDisabled();
  await page.getByLabel('人工核验说明', { exact: true }).fill('已核对真实答案，拒答行为和资料范围一致');
  await page.getByRole('button', { name: '确认回归并解决' }).click();
  await expect(page.getByRole('dialog').getByText('已解决', { exact: true })).toBeVisible();
  const correction = state.writes.find((write) => write.action === 'correction')!.body.input as Record<string, unknown>;
  expect(correction.expected_refusal).toBe(true); expect(correction.evidences).toEqual([]);
  expect(state.writes.map((write) => write.action)).toEqual(['create', 'claim', 'correction', 'resolve']);
  expect(unexpectedRequests).toEqual([]);
  expect(warnings.filter((text) => /circular|useForm|initialValues/.test(text))).toEqual([]);
});

test('零命中入口提交随机洞察标识', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  await page.goto('/kb/kb_fixture');
  await page.getByRole('tab', { name: '质量与反馈', exact: true }).click();
  await page.getByRole('tab', { name: '检索洞察', exact: true }).click();
  await page.getByRole('button', { name: '处理问题', exact: true }).click();
  expect(state.writes[0]).toEqual({ action: 'create', body: { source_type: 'ZERO_HIT', source_id: 'insight_opaque' } });
  expect(unexpectedRequests).toEqual([]);
});

test('返回页面刷新不覆盖草稿，撤权后清除内容', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  await openIssue(page); await page.getByRole('button', { name: '领取问题', exact: true }).click();
  await fillCorrection(page);
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByLabel('纠正说明', { exact: true })).toHaveValue('已核对资料范围，没有可用于回答的依据');
  state.denied = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByText('相关资料或应用的访问权限已变化，内容已隐藏。请恢复权限后重新加载。')).toBeVisible();
  await expect(page.getByLabel('纠正说明', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('dialog').getByText(state.issue.summary!, { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('冲突保留草稿并停止重复提交', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  await openIssue(page); await page.getByRole('button', { name: '领取问题', exact: true }).click();
  await fillCorrection(page); state.conflict = true;
  await page.getByRole('button', { name: '保存纠正用例', exact: true }).click();
  await expect(page.getByRole('dialog').getByText('用例已被其他页面更新', { exact: true })).toBeVisible();
  await expect(page.getByLabel('纠正说明', { exact: true })).toHaveValue('已核对资料范围，没有可用于回答的依据');
  await expect(page.getByRole('button', { name: '保存纠正用例', exact: true })).toBeDisabled();
  expect(state.writes.filter((write) => write.action === 'correction')).toHaveLength(1);
  expect(unexpectedRequests).toEqual([]);
});

test('正常回答必须填写标准和正确证据，不能直接使用 BAD 分片', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  await openIssue(page); await page.getByRole('button', { name: '领取问题', exact: true }).click();
  await fillCorrection(page); await page.getByLabel('正确行为应为拒答', { exact: true }).click();
  await page.getByRole('button', { name: '保存纠正用例', exact: true }).click();
  await expect(page.getByText('正常回答至少需要一条正确证据', { exact: true })).toBeVisible();
  expect(state.writes.filter((write) => write.action === 'correction')).toHaveLength(0);
  await page.getByLabel('标准答案', { exact: true }).fill('以当前制度中的适用期限为准');
  await page.getByRole('button', { name: '添加正确证据', exact: true }).click();
  await choose(page, '证据文档 1', documents[0].file_name);
  await page.getByLabel('正确证据原文', { exact: true }).fill('适用期限自签收之日起计算');
  await page.getByRole('button', { name: '保存纠正用例', exact: true }).click();
  await expect(page.getByText('本次纠正的验收标准', { exact: true })).toBeVisible();
  expect(state.issue.correction?.evidences).toEqual([{ doc_id: 'doc_0', span: '适用期限自签收之日起计算' }]);
  expect(state.issue.correction?.expected_refusal).toBe(false);
  expect(unexpectedRequests).toEqual([]);
});

test('语料发生变化时即使已有成功运行也不能解决', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  await openIssue(page); await page.getByRole('button', { name: '领取问题', exact: true }).click();
  await fillCorrection(page); await page.getByRole('button', { name: '保存纠正用例', exact: true }).click();
  state.regressionError = true;
  await page.getByRole('combobox', { name: '已完成的答案评测', exact: true }).click();
  await page.locator('.ant-select-dropdown:visible').getByText(/纠正后回归/).click();
  await expect(page.getByRole('dialog').getByText('当前语料已变化，请重新评测', { exact: true })).toBeVisible();
  await page.getByLabel('人工核验说明', { exact: true }).fill('旧结果不能用于确认新语料');
  await expect(page.getByRole('button', { name: '确认回归并解决' })).toBeDisabled();
  expect(state.writes.some((write) => write.action === 'resolve')).toBe(false);
  expect(unexpectedRequests).toEqual([]);
});

test('写入响应丢失后停止提交，人工载入后以服务器记录为准', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  await openIssue(page); await page.getByRole('button', { name: '领取问题', exact: true }).click();
  await fillCorrection(page);
  await page.route(`**/api/v1${root}/issue_quality/correction`, async (route) => {
    state.writes.push({ action: 'correction', body: route.request().postDataJSON() });
    state.issue.revision += 1;
    await route.abort('failed');
  });
  await page.getByRole('button', { name: '保存纠正用例', exact: true }).click();
  await expect(page.getByRole('dialog').getByText('保存结果尚未确认，请刷新状态后重试', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '保存纠正用例' })).not.toBeVisible();
  await page.getByRole('button', { name: '载入最新内容' }).click();
  await page.getByRole('button', { name: '放弃更改' }).click();
  await expect(page.getByLabel('纠正说明', { exact: true })).toHaveValue('');
  expect(state.writes.filter((write) => write.action === 'correction')).toHaveLength(1);
  expect(unexpectedRequests).toEqual([]);
});

test('释放后保留处理记录，重新领取可以继续处理', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  await openIssue(page); await page.getByRole('button', { name: '领取问题', exact: true }).click();
  await page.getByLabel('补充处理说明', { exact: true }).fill('已确认缺少最新制度，等待补充');
  await page.getByRole('button', { name: '保存处理说明', exact: true }).click();
  await expect(page.getByText('已确认缺少最新制度，等待补充', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '释放给其他人', exact: true }).click();
  await expect(page.getByRole('button', { name: '领取问题', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '领取问题', exact: true }).click();
  await expect(page.getByText('已确认缺少最新制度，等待补充', { exact: true })).toBeVisible();
  expect(state.writes.map((write) => write.action)).toEqual(['claim','notes','release','claim']);
  expect(unexpectedRequests).toEqual([]);
});

test('质量问题深链打开指定评测集和任务页，非法目标不会被查询', async ({ page, api, unexpectedRequests }) => {
  await fixture(page, api);
  api['/eval-datasets/ds_quality/cases'] = pageData([]);
  await page.goto('/eval?kb_id=kb_fixture&dataset_id=ds_quality&tab=run');
  await expect(page.getByRole('tab', { name: '评测任务与报告', exact: true })).toHaveAttribute('aria-selected','true');
  await expect(page.getByText('新建评测运行', { exact: true })).toBeVisible();
  await page.goto('/eval?kb_id=kb_fixture&dataset_id=ds_quality&tab=cases');
  await expect(page.getByText(new RegExp(`当前评测集：${reference.name}`))).toBeVisible();
  await page.goto('/eval?kb_id=unauthorized&dataset_id=outside&tab=run');
  await expect(page.getByText('请先在「评测集管理」选择一个评测集', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('切换知识库后立即隐藏上一知识库的问题，迟到读取不能恢复旧详情', async ({ page, api, unexpectedRequests }) => {
  const state = await fixture(page, api);
  api['/kb/second'] = { ...(api['/kb/kb_fixture'] as object), kb_id: 'second', name: '另一个知识库' };
  api['/kb/second/documents'] = pageData([]);
  api['/kb/second/rebuild-status'] = { stale_count:0, in_progress_count:0, failed_count:0 };
  let unblock!: () => void;
  const pending = new Promise<void>((resolve) => { unblock = resolve; });
  let observed!: () => void;
  const requested = new Promise<void>((resolve) => { observed = resolve; });
  await page.route('**/api/v1/kb/second/quality-issues**', async (route) => {
    expect(route.request().method()).toBe('GET');
    observed(); await pending;
    await route.fulfill({ json: { code:'OK', data:pageData([]) } });
  });
  await openIssue(page);
  await page.evaluate(() => { history.pushState({}, '', '/kb/second'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await requested;
  try {
    await expect(page.getByRole('dialog', { name:'处理质量问题' })).toHaveCount(0);
    await expect(page.getByText(state.issue.summary!, { exact:true })).toHaveCount(0);
  } finally { unblock(); }
  expect(unexpectedRequests).toEqual([]);
});

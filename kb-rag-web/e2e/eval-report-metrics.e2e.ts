import { test, expect, kb, app, pageData } from './fixtures';

// 使用后端真实分组契约 overall，避免把前端假定的 all 写回测试数据。
const metric = (value: number) => ({ recall: value, precision: value, hit_rate: value, mrr: value, ndcg: value });
const run = {
  run_id: 'run_report', dataset_id: 'dataset_report', kb_id: kb.kb_id, dataset_revision: 1,
  status: 'SUCCESS', case_total: 1, case_effective: 1, case_stale: 0, case_degraded: 0,
  retrieval_config: { label: '契约验证', mode: 'HYBRID_RERANK' },
  metrics: { overall: { '10': metric(0.8) }, multi_turn: { '10': metric(0.6) } },
  answer_metrics: null, created_at: '2026-09-15T01:00:00Z',
};

test('正式报告读取 overall 指标并导出真实数值，切换多轮后读取对应分组', async ({ page, api, unexpectedRequests }) => {
  api['/kb/kb_fixture/eval-datasets'] = [{
    dataset_id: 'dataset_report', kb_id: kb.kb_id, name: '正式标准', dataset_revision: 1, case_count: 1,
  }];
  api['/eval-datasets/dataset_report/runs'] = pageData([run]);
  api['/eval-datasets/dataset_report/cases'] = pageData([]);
  api['/eval-runs/run_report'] = run;
  api['/eval-runs/run_report/results'] = pageData([]);
  await page.goto('/eval?kb_id=kb_fixture&dataset_id=dataset_report&tab=run');
  await page.getByRole('button', { name: '查看报告', exact: true }).click();
  const report = page.getByRole('dialog', { name: '评测报告', exact: true });
  await expect(report.getByRole('row').filter({ hasText: 'Recall@10' })).toContainText('80.0%', { timeout: 3000 });
  const downloadPromise = page.waitForEvent('download');
  await report.getByRole('button', { name: '导出指标 CSV' }).focus();
  await page.keyboard.press('Enter');
  const download = await downloadPromise;
  const stream = await download.createReadStream();
  expect(stream).not.toBeNull();
  let csv = '';
  for await (const chunk of stream!) csv += chunk.toString('utf8');
  expect(csv).toContain('Recall@10');
  expect(csv).toContain('80.0%');
  await report.getByText('多轮', { exact: true }).click();
  await expect(report.getByRole('row').filter({ hasText: 'Recall@10' })).toContainText('60.0%');
  expect(unexpectedRequests).toEqual([]);
});

test('发布门禁双跑默认展示 overall 组的候选与基线指标', async ({ page, api, unexpectedRequests }) => {
  api['/apps/app_fixture/versions'] = [{
    app_version_id: 'version_report', app_id: app.app_id, version: 'V3.0', status: 'GATE_PASSED',
    config: { kb_refs: [{ kb_id: kb.kb_id, weight: 1 }],
      retrieval: { recall_top_k: 50, top_n: 10, fusion_mode: 'rrf', rerank_enabled: true, rewrite_enabled: false },
      prompt: { system_prompt: '依据资料回答', refusal_enabled: true, leak_guard_enabled: true, citation_enabled: true } },
    gate_dataset_id: null,
    gate_run_ids: ['run_report', 'run_baseline'], gate_verdict: '通过', force_released: false,
    created_at: '2026-09-15T01:00:00Z', updated_at: '2026-09-15T01:00:00Z',
  }];
  api['/eval-runs/compare'] = { comparable: true, reason: null,
    runs: [run, { ...run, run_id: 'run_baseline', metrics: { overall: { '10': metric(0.7) } } }] };
  await page.goto('/apps/app_fixture');
  await page.getByRole('tab', { name: '版本与发布', exact: true }).click();
  await page.getByRole('button', { name: '查看双跑结果', exact: true }).click();
  const report = page.getByRole('dialog', { name: '发布门禁双跑对比', exact: true });
  const recall = report.getByRole('row').filter({ hasText: 'Recall@10' });
  await expect(recall).toContainText('80.0%', { timeout: 3000 });
  await expect(recall).toContainText('70.0%');
  expect(unexpectedRequests).toEqual([]);
});

import type { Page } from '@playwright/test';
import { expect, pageData, app } from './fixtures';
import type { QualityIssue, QualityIssueRecord } from '../src/api/qualityIssue';

export const root = '/kb/kb_fixture/quality-issues';
export const standard = '对知识库之外的问题明确说明资料不足';
export const reference = { dataset_id: 'ds_quality', kb_id: 'kb_fixture', name: '质量回归基线', case_count: 1, dataset_revision: 1, last_run: null };
export const version = { app_version_id: 'v13', app_id: 'app_fixture', version: 'v1.3', status: 'RELEASED', config: { kb_refs: [{ kb_id: 'kb_fixture', weight: 1 }] } };

export async function fixture(page: Page, api: Record<string, unknown>) {
  const issue: QualityIssue = { issue_id: 'issue_quality', kb_id: 'kb_fixture', source_type: 'BAD_FEEDBACK', source_id: 'feedback_quality',
    summary: '找不到适用的售后规则', status: 'NEW', revision: 0, owner_user_id: null, owner_name: null, owned_by_me: false,
    reason: null, dataset_id: null, case_id: null, case_revision: null, affected_app_version_id: null,
    verified_run_id: null, verified_app_version_id: null, content_restricted: false, correction: null,
    created_at: '2026-09-01T08:00:00', resolved_at: null };
  const state = { issue, writes: [] as { action: string; body: Record<string, unknown> }[], denied: false, conflict: false, regressionError: false, records: [] as QualityIssueRecord[] };
  api['/kb/kb_fixture/eval-datasets'] = [reference];
  api['/apps/app_fixture/versions'] = [version]; api['/app-versions/v13'] = version;
  api['/kb/kb_fixture/retrieval-feedback'] = pageData([{ feedback_id: 'feedback_quality', kb_id: 'kb_fixture', query: issue.summary,
    chunk_id: 'chunk_bad', doc_id: 'doc_0', verdict: 'BAD', status: 'NEW', channel: 'CONSOLE', created_at: '2026-09-01T08:00:00' }]);
  api['/kb/kb_fixture/search-insights/stats'] = { total: 10, zero_hit_count: 7, zero_hit_rate: 0.7, degraded_count: 0,
    top_zero_hit_queries: [{ insight_id: 'insight_opaque', query_digest: '已脱敏的零命中问题', count: 7, last_at: '2026-09-01T08:00:00' }] };
  api['/kb/kb_fixture/search-insights'] = pageData([]);
  api['/eval-datasets/ds_quality/runs'] = pageData([{ run_id: 'run_quality', dataset_id: 'ds_quality', kb_id: 'kb_fixture', status: 'SUCCESS',
    answer_evaluation: { app_version_id: 'v13', generation_model: 'fixture' }, retrieval_config: { label: '纠正后回归', mode: 'HYBRID' },
    finished_at: '2026-09-01T09:00:00', case_total: 1, case_effective: 1, case_stale: 0, case_degraded: 0 }]);
  await page.route('**/api/v1/kb/kb_fixture/quality-issues**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/v1', '');
    const ok = (data: unknown) => route.fulfill({ json: { code: 'OK', message: 'success', data } });
    if (state.denied) return route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: '资料权限已撤销' } });
    if (request.method() === 'GET') {
      if (path === root) return ok({ ...pageData([state.issue]), size: 20 });
      if (path === `${root}/issue_quality`) return ok(state.issue);
      if (path.endsWith('/records')) return ok({ ...pageData(state.records), size: 50 });
      if (path.endsWith('/regressions/run_quality') && state.regressionError) return route.fulfill({ status: 400, json: { code: 'INVALID_PARAM', message: '当前语料已变化，请重新评测' } });
      if (path.endsWith('/regressions/run_quality')) return ok({ app_version_id: 'v13', minimum_dimension_score: 4,
        result: { case_id: 'case_quality', generated_answer: standard, answer_correctness: 5, answer_faithfulness: 5,
          answer_completeness: 4, citation_correctness: 5, citation_completeness: 5, answer_judge_reason: '符合标准拒答行为' } });
    }
    if (request.method() === 'POST') {
      const body = request.postDataJSON();
      const action = path === root ? 'create' : path.split('/').at(-1)!;
      state.writes.push({ action, body });
      if (state.conflict && action === 'correction') {
        return route.fulfill({ status: 409, json: { code: 'EVAL_DATASET_CONFLICT', message: '用例已被其他页面更新' } });
      }
      if (action === 'create') return ok(state.issue);
      if (action === 'claim') { issue.status = 'IN_PROGRESS'; issue.owner_name = '测试用户'; issue.owner_user_id = 'user_fixture'; issue.owned_by_me = true; }
      else if (action === 'correction') {
        issue.status = 'WAITING_REGRESSION'; issue.reason = body.reason; issue.dataset_id = body.dataset_id; issue.case_id = 'case_quality';
        issue.case_revision = 0; issue.affected_app_version_id = body.affected_app_version_id;
        issue.correction = { ...body.input, case_id: 'case_quality', dataset_id: body.dataset_id, source: 'MANUAL', status: 'ACTIVE' };
      } else if (action === 'resolve') {
        issue.status = 'RESOLVED'; issue.verified_run_id = body.run_id; issue.verified_app_version_id = 'v13'; issue.resolved_at = '2026-09-01T10:00:00';
      } else if (action === 'release' || action === 'reopen') {
        issue.status = 'NEW'; issue.owner_user_id = null; issue.owner_name = null; issue.owned_by_me = false;
      } else if (action !== 'notes') throw new Error(`Unexpected quality action ${action}`);
      issue.revision += 1;
      state.records.unshift({ action: action === 'claim' ? 'CLAIMED' : action === 'correction' ? 'CORRECTED' : action === 'resolve' ? 'RESOLVED' : action === 'release' ? 'RELEASED' : action === 'reopen' ? 'REOPENED' : 'NOTE_ADDED',
        actor_name: '测试用户', note: body.note ?? body.input?.note ?? null, case_id: issue.case_id,
        run_id: body.run_id ?? null, created_at: '2026-09-01T10:00:00' });
      return ok(issue);
    }
    throw new Error(`Unexpected quality request ${request.method()} ${path}`);
  });
  return state;
}

export async function openIssue(page: Page) {
  await page.goto('/kb/kb_fixture');
  await page.getByRole('tab', { name: '质量与反馈', exact: true }).click();
  await page.getByRole('button', { name: '查看处理', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '处理质量问题' })).toBeVisible();
}

export async function choose(page: Page, field: string, label: string) {
  const control = page.getByRole('combobox', { name: new RegExp(`^${field}(?:（必填）)?$`) });
  await expect(control).toBeEnabled();
  await control.focus();
  await control.press('ArrowDown');
  await page.locator('.ant-select-dropdown:visible').getByText(label, { exact: true }).click();
}

export async function fillCorrection(page: Page) {
  await page.getByRole('button', { name: '填写纠正方案', exact: true }).click();
  await choose(page, '保存到评测集', reference.name);
  await choose(page, '受影响应用', app.name);
  await choose(page, '发生问题的版本', version.version);
  await page.getByLabel('用于回归的完整问题', { exact: true }).fill('知识库未包含的问题应该如何处理？');
  await page.getByLabel('正确行为应为拒答', { exact: true }).click();
  await page.getByLabel('纠正说明', { exact: true }).fill('已核对资料范围，没有可用于回答的依据');
}

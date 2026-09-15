import axios from 'axios';
import { apiGet, apiPost } from './request';
import type { CreateEvalCaseRequest, EvalCase, EvalResult, PageResult } from './types';

export type QualityIssueStatus = 'NEW' | 'IN_PROGRESS' | 'WAITING_REGRESSION' | 'RESOLVED';
export type QualityIssueSource = 'BAD_FEEDBACK' | 'ZERO_HIT' | 'EMPLOYEE_ANSWER';
export type QualityIssueReason = 'MISSING_KNOWLEDGE' | 'OUTDATED_KNOWLEDGE' | 'RETRIEVAL_MISS' | 'ANSWER_INCORRECT' | 'QUESTION_UNCLEAR' | 'OTHER';
export type QualityIssueAction = 'claim' | 'release' | 'notes' | 'resolve' | 'reopen';

export interface QualityIssue {
  issue_id: string; kb_id: string; source_type: QualityIssueSource; source_id: string | null;
  summary: string | null; status: QualityIssueStatus; revision: number;
  owner_user_id: string | null; owner_name: string | null; owned_by_me: boolean; reason: QualityIssueReason | null;
  dataset_id: string | null; case_id: string | null; case_revision: number | null;
  affected_app_version_id: string | null; verified_run_id: string | null; verified_app_version_id: string | null;
  content_restricted: boolean; created_at: string | null; resolved_at: string | null; correction: EvalCase | null;
}

export interface QualityIssueRecord {
  action: 'CREATED' | 'CLAIMED' | 'RELEASED' | 'CORRECTED' | 'NOTE_ADDED' | 'RESOLVED' | 'REOPENED';
  note: string | null; actor_name: string; case_id: string | null; run_id: string | null; created_at: string | null;
}

export interface QualityCorrection {
  revision: number; case_revision: number | null; reason: QualityIssueReason; dataset_id: string;
  affected_app_version_id: string; input: CreateEvalCaseRequest & { expected_refusal: boolean; note: string };
}

export interface QualityRegression { app_version_id: string; minimum_dimension_score: number; result: EvalResult }

function root(kbId: string) { return `/kb/${encodeURIComponent(kbId)}/quality-issues`; }
function detail(kbId: string, issueId: string) { return `${root(kbId)}/${encodeURIComponent(issueId)}`; }

/** 重复来源由服务端去重；浏览器不自动重试任何写操作。 */
export function createQualityIssue(kbId: string, sourceType: QualityIssueSource, sourceId: string): Promise<QualityIssue> {
  return apiPost(root(kbId), { source_type: sourceType, source_id: sourceId });
}
export function listQualityIssues(kbId: string, params: { status?: QualityIssueStatus; mine?: boolean; page: number }): Promise<PageResult<QualityIssue>> {
  return apiGet(root(kbId), { ...params, size: 20 });
}
export function getQualityIssue(kbId: string, issueId: string): Promise<QualityIssue> { return apiGet(detail(kbId, issueId)); }
export function listQualityIssueRecords(kbId: string, issueId: string, page: number): Promise<PageResult<QualityIssueRecord>> {
  return apiGet(`${detail(kbId, issueId)}/records`, { page });
}
export function updateQualityIssue(kbId: string, issueId: string, action: QualityIssueAction,
  payload: { revision: number; note?: string; run_id?: string }): Promise<QualityIssue> {
  return apiPost(`${detail(kbId, issueId)}/${action}`, payload);
}
export function correctQualityIssue(kbId: string, issueId: string, payload: QualityCorrection): Promise<QualityIssue> {
  return apiPost(`${detail(kbId, issueId)}/correction`, payload);
}
export function getQualityRegression(kbId: string, issueId: string, runId: string): Promise<QualityRegression> {
  return apiGet(`${detail(kbId, issueId)}/regressions/${encodeURIComponent(runId)}`);
}

/** 结果不明、修订冲突与撤权需要不同交互，避免保留受限内容或重复写入。 */
export function qualityFailure(error: unknown): { message: string; conflict: boolean; restricted: boolean; uncertain: boolean } {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    return {
      message: error.response?.data?.message ?? '保存结果尚未确认，请刷新状态后重试',
      conflict: status === 409, restricted: status === 401 || status === 403 || status === 404,
      uncertain: !error.response,
    };
  }
  return { message: error instanceof Error ? error.message : '操作未完成，请重试', conflict: false, restricted: false, uncertain: false };
}

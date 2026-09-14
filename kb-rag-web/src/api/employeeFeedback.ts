import { apiGet } from './request';
import type { PageResult } from './types';

export interface EmployeeFeedbackSummary {
  run_id: string; question: string | null; feedback_note: string | null;
  feedback_updated_at: string | null; content_restricted: boolean;
}
export interface EmployeeFeedbackDetail {
  run_id: string; question: string; answer: string; feedback_verdict: 'GOOD' | 'BAD';
  feedback_note: string | null; feedback_updated_at: string | null; app_version: string;
  citations: { file_name: string; content: string; inherited: boolean }[];
}

function root(kbId: string) { return `/kb/${encodeURIComponent(kbId)}/employee-feedback`; }
export function listEmployeeFeedback(kbId: string, page: number): Promise<PageResult<EmployeeFeedbackSummary>> {
  return apiGet(root(kbId), { page, size: 20 });
}
export function getEmployeeFeedback(kbId: string, runId: string): Promise<EmployeeFeedbackDetail> {
  return apiGet(`${root(kbId)}/${encodeURIComponent(runId)}`);
}

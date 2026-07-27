// Author: owlzhangfq@gmail.com
import { apiGet, apiPost } from './request';
import type {
  CreateEvalRunRequest,
  EvalResult,
  EvalRun,
  EvalRunCompareResult,
  EvalRunEstimate,
  PageResult,
} from './types';

/**
 * POST /api/v1/eval-datasets/{datasetId}/runs (M4b-CONTRACTS.md section 3.1): one submission with
 * N configs (1..6) creates N runs, one per config, sharing dataset_revision/corpus_fingerprint.
 */
export function createEvalRuns(datasetId: string, payload: CreateEvalRunRequest): Promise<EvalRun[]> {
  return apiPost<EvalRun[]>(`/eval-datasets/${datasetId}/runs`, payload);
}

/**
 * POST /api/v1/eval-datasets/{datasetId}/runs/estimate (M4b-CONTRACTS.md section 3.4): cost
 * guardrail the run form must call and display before "提交创建 Run" is enabled.
 */
export function estimateEvalRun(datasetId: string, payload: CreateEvalRunRequest): Promise<EvalRunEstimate> {
  return apiPost<EvalRunEstimate>(`/eval-datasets/${datasetId}/runs/estimate`, payload);
}

export function getEvalRun(runId: string): Promise<EvalRun> {
  return apiGet<EvalRun>(`/eval-runs/${runId}`);
}

/** GET /api/v1/eval-datasets/{datasetId}/runs?page= (M4b-CONTRACTS.md section 3.1). */
export function listEvalRuns(datasetId: string, page?: number): Promise<PageResult<EvalRun>> {
  return apiGet<PageResult<EvalRun>>(`/eval-datasets/${datasetId}/runs`, { page });
}

export interface ListEvalResultsParams {
  hit?: boolean;
  page?: number;
}

/** GET /api/v1/eval-runs/{runId}/results?hit=&page= (M4b-CONTRACTS.md section 3.1): per-case hit detail for drill-down. */
export function listEvalResults(runId: string, params?: ListEvalResultsParams): Promise<PageResult<EvalResult>> {
  return apiGet<PageResult<EvalResult>>(`/eval-runs/${runId}/results`, params);
}

// Same bounded-loop rationale as evalCase.ts's listAllEvalCases.
const MAX_RESULT_PAGES = 200;

/** Pages through every result row of a run, for the report drawer's full per-case drill-down table. */
export async function listAllEvalResults(runId: string): Promise<EvalResult[]> {
  const all: EvalResult[] = [];
  for (let page = 1; page <= MAX_RESULT_PAGES; page += 1) {
    const result = await listEvalResults(runId, { page });
    all.push(...result.items);
    if (result.items.length === 0 || all.length >= result.total) {
      break;
    }
  }
  return all;
}

/**
 * GET /api/v1/eval-runs/compare?run_ids=a,b,c (M4b-CONTRACTS.md section 3.1): server judges
 * comparability (same dataset_revision) instead of the client re-deriving that rule.
 */
export function compareEvalRuns(runIds: string[]): Promise<EvalRunCompareResult> {
  return apiGet<EvalRunCompareResult>('/eval-runs/compare', { run_ids: runIds.join(',') });
}

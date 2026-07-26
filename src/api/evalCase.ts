// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  CaseStatus,
  CreateEvalCaseFromRetrievalRequest,
  CreateEvalCaseRequest,
  EvalCase,
  PageResult,
  RecheckCaseRequest,
  StaleCaseItem,
  UpdateEvalCaseRequest,
} from './types';

/** POST /api/v1/eval-datasets/{datasetId}/cases (M4b-CONTRACTS.md section 2): bumps dataset_revision by 1 server-side. */
export function createEvalCase(datasetId: string, payload: CreateEvalCaseRequest): Promise<EvalCase> {
  return apiPost<EvalCase>(`/eval-datasets/${datasetId}/cases`, payload);
}

export interface ListEvalCasesParams {
  status?: CaseStatus;
  page?: number;
}

/** GET /api/v1/eval-datasets/{datasetId}/cases?status=&page= (M4b-CONTRACTS.md section 2). */
export function listEvalCases(datasetId: string, params?: ListEvalCasesParams): Promise<PageResult<EvalCase>> {
  return apiGet<PageResult<EvalCase>>(`/eval-datasets/${datasetId}/cases`, params);
}

// Bounds the page-walk below so a corrupt/huge dataset cannot spin this into an unbounded loop;
// well beyond any realistic eval dataset size at this stage of the product.
const MAX_CASE_PAGES = 200;

/**
 * Pages through every case of a dataset. Used by the report drawer's per-case drill-down (needs
 * query/anchor_type/messages for every case_id that appears in a run's results) and by the review
 * tab's stale-count display; listEvalCases only exposes page-number pagination with a
 * server-fixed page size, so there is no single "get all" endpoint to call instead.
 */
export async function listAllEvalCases(datasetId: string, status?: CaseStatus): Promise<EvalCase[]> {
  const all: EvalCase[] = [];
  for (let page = 1; page <= MAX_CASE_PAGES; page += 1) {
    const result = await listEvalCases(datasetId, { status, page });
    all.push(...result.items);
    if (result.items.length === 0 || all.length >= result.total) {
      break;
    }
  }
  return all;
}

/** PUT /api/v1/eval-cases/{caseId} (M4b-CONTRACTS.md section 2): bumps dataset_revision by 1 server-side. */
export function updateEvalCase(caseId: string, payload: UpdateEvalCaseRequest): Promise<EvalCase> {
  return apiPut<EvalCase>(`/eval-cases/${caseId}`, payload);
}

/** DELETE /api/v1/eval-cases/{caseId} (M4b-CONTRACTS.md section 2): bumps dataset_revision by 1 server-side. */
export function deleteEvalCase(caseId: string): Promise<void> {
  return apiDelete<void>(`/eval-cases/${caseId}`);
}

/**
 * POST /api/v1/eval-cases/{caseId}/recheck (M4b-CONTRACTS.md section 2): REANCHOR replaces the
 * case's evidences with the given list and resets status back to ACTIVE; DEPRECATE marks the case
 * DEPRECATED and ignores `evidences`.
 */
export function recheckEvalCase(caseId: string, payload: RecheckCaseRequest): Promise<EvalCase> {
  return apiPost<EvalCase>(`/eval-cases/${caseId}/recheck`, payload);
}

/** GET /api/v1/eval-datasets/{datasetId}/stale-cases (M4b-CONTRACTS.md section 2): evidence review workbench data source. */
export function listStaleCases(datasetId: string): Promise<StaleCaseItem[]> {
  return apiGet<StaleCaseItem[]>(`/eval-datasets/${datasetId}/stale-cases`);
}

/**
 * POST /api/v1/eval-datasets/{datasetId}/cases/from-retrieval (M4b-CONTRACTS.md section 2):
 * one-click "收进评测集" from the search debug page; source=DEBUG_PAGE server-side.
 */
export function createEvalCaseFromRetrieval(
  datasetId: string,
  payload: CreateEvalCaseFromRetrievalRequest,
): Promise<EvalCase> {
  return apiPost<EvalCase>(`/eval-datasets/${datasetId}/cases/from-retrieval`, payload);
}

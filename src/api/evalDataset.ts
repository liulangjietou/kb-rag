// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost } from './request';
import type { CreateEvalDatasetRequest, EvalDataset, ImportDemoEvalDatasetResult } from './types';

/** POST /api/v1/kb/{kbId}/eval-datasets (M4b-CONTRACTS.md section 2). */
export function createEvalDataset(kbId: string, payload: CreateEvalDatasetRequest): Promise<EvalDataset> {
  return apiPost<EvalDataset>(`/kb/${kbId}/eval-datasets`, payload);
}

/** GET /api/v1/kb/{kbId}/eval-datasets: list includes case_count/dataset_revision/last_run summary. */
export function listEvalDatasets(kbId: string): Promise<EvalDataset[]> {
  return apiGet<EvalDataset[]>(`/kb/${kbId}/eval-datasets`);
}

export function getEvalDataset(datasetId: string): Promise<EvalDataset> {
  return apiGet<EvalDataset>(`/eval-datasets/${datasetId}`);
}

/**
 * DELETE /api/v1/eval-datasets/{datasetId} (M4b-CONTRACTS.md section 2): cascades to the dataset's
 * cases and runs/results server-side (soft delete + after-commit cleanup).
 */
export function deleteEvalDataset(datasetId: string): Promise<void> {
  return apiDelete<void>(`/eval-datasets/${datasetId}`);
}

/**
 * POST /api/v1/kb/{kbId}/eval-datasets/import-demo (M4b-CONTRACTS.md section 2): imports the
 * deploy repo's demo/eval-cases.json, matching cases to this KB's documents by
 * file_name + content_hash_sha256. Idempotent -- a repeat call returns the existing dataset's id
 * without re-importing (see ImportDemoEvalDatasetResult.already_existed).
 */
export function importDemoEvalDataset(kbId: string): Promise<ImportDemoEvalDatasetResult> {
  return apiPost<ImportDemoEvalDatasetResult>(`/kb/${kbId}/eval-datasets/import-demo`);
}

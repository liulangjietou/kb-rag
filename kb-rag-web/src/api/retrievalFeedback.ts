// Author: owlzhangfq@gmail.com
import { apiPost } from './request';

/**
 * 好/坏 verdict on one retrieval result (需求 §4.5 "检索结果反馈标注").
 */
export type RetrievalVerdict = 'GOOD' | 'BAD';

export interface RetrievalFeedbackRequest {
  kb_id: string;
  query: string;
  chunk_id: string;
  verdict: RetrievalVerdict;
}

/**
 * POST /api/v1/retrieval-feedback (M4b-CONTRACTS.md section 2).
 *
 * Deliberately a log-only endpoint on the server: the payload carries no dataset_id, so a BAD
 * verdict has nowhere safe to land and no consumer in this milestone. The contract's own wording
 * (§8 of the M4b deviations) is that GOOD's real persistence path is the separate "收进评测集"
 * endpoint (POST cases/from-retrieval), which this page also exposes. Marking 好 here therefore
 * records the signal without creating a case -- use 收进评测集 when a case is what you want.
 */
export function submitRetrievalFeedback(payload: RetrievalFeedbackRequest): Promise<void> {
  return apiPost<void>('/retrieval-feedback', payload);
}

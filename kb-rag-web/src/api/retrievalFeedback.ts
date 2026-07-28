// Author: owlzhangfq@gmail.com
import { apiGet, apiPost } from './request';
import type {
  ConvertFeedbackRequest,
  FeedbackVerdict,
  ListRetrievalFeedbackParams,
  PageResult,
  RetrievalFeedbackEntry,
} from './types';

/**
 * 好/坏 verdict on one retrieval result (需求 §4.5 "检索结果反馈标注"). Alias of the shared
 * FeedbackVerdict, kept so the search page's existing imports need no churn.
 */
export type RetrievalVerdict = FeedbackVerdict;

export interface RetrievalFeedbackRequest {
  kb_id: string;
  query: string;
  chunk_id: string;
  verdict: RetrievalVerdict;
}

/**
 * POST /api/v1/retrieval-feedback (M10-CONTRACTS.md section 2.1).
 *
 * The payload is unchanged from M4b -- the M10 compatibility line -- but the endpoint is no
 * longer log-only: the verdict now lands as a persisted row (status=NEW) that the KB detail
 * page's feedback management tab can list, convert into an evaluation case or dismiss.
 */
export function submitRetrievalFeedback(payload: RetrievalFeedbackRequest): Promise<RetrievalFeedbackEntry> {
  return apiPost<RetrievalFeedbackEntry>('/retrieval-feedback', payload);
}

/** GET /api/v1/kb/{kbId}/retrieval-feedback (M10-CONTRACTS.md section 2.1), newest first. */
export function listRetrievalFeedback(
  kbId: string,
  params?: ListRetrievalFeedbackParams,
): Promise<PageResult<RetrievalFeedbackEntry>> {
  return apiGet<PageResult<RetrievalFeedbackEntry>>(`/kb/${kbId}/retrieval-feedback`, params);
}

/**
 * POST /api/v1/retrieval-feedback/{feedbackId}/convert (M10-CONTRACTS.md section 2.1): turns one
 * GOOD, still-NEW feedback into an evaluation case (source=FEEDBACK) of the given dataset.
 * BAD rows and rows already CONVERTED/DISMISSED are rejected server-side with INVALID_PARAM.
 */
export function convertRetrievalFeedback(
  feedbackId: string,
  payload: ConvertFeedbackRequest,
): Promise<RetrievalFeedbackEntry> {
  return apiPost<RetrievalFeedbackEntry>(`/retrieval-feedback/${feedbackId}/convert`, payload);
}

/** POST /api/v1/retrieval-feedback/{feedbackId}/dismiss (M10-CONTRACTS.md section 2.1): terminal, cannot be undone. */
export function dismissRetrievalFeedback(feedbackId: string): Promise<RetrievalFeedbackEntry> {
  return apiPost<RetrievalFeedbackEntry>(`/retrieval-feedback/${feedbackId}/dismiss`);
}

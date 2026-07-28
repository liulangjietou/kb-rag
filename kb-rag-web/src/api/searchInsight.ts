// Author: owlzhangfq@gmail.com
import { apiGet } from './request';
import type {
  ListSearchInsightParams,
  PageResult,
  SearchInsightEntry,
  SearchInsightStats,
} from './types';

/**
 * GET /api/v1/kb/{kbId}/search-insights (M10-CONTRACTS.md section 2.2), newest first. Rows are
 * recorded automatically at retrieval time (console debug + OpenAPI); evaluation runs never
 * produce them, so this listing is real traffic only.
 */
export function listSearchInsights(
  kbId: string,
  params?: ListSearchInsightParams,
): Promise<PageResult<SearchInsightEntry>> {
  return apiGet<PageResult<SearchInsightEntry>>(`/kb/${kbId}/search-insights`, params);
}

/**
 * GET /api/v1/kb/{kbId}/search-insights/stats (M10-CONTRACTS.md section 2.2): the content-gap
 * report -- totals, zero-hit rate, degraded count and the Top zero-hit query groups
 * (case/whitespace-normalized, digest of the newest row per group).
 */
export function getSearchInsightStats(
  kbId: string,
  params?: Pick<ListSearchInsightParams, 'from' | 'to'>,
): Promise<SearchInsightStats> {
  return apiGet<SearchInsightStats>(`/kb/${kbId}/search-insights/stats`, params);
}

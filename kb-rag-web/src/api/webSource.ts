// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type { PageResult, RegisterWebSourceRequest, WebSourceEntry } from './types';

/**
 * POST /api/v1/kb/{kbId}/web-sources (M12-CONTRACTS.md section 3.4): registers a page URL and
 * runs its first fetch inline, so the returned entry already carries the outcome -- FAILED here
 * means the registration stuck but the page could not be fetched (the row keeps the error).
 */
export function registerWebSource(kbId: string, payload: RegisterWebSourceRequest): Promise<WebSourceEntry> {
  return apiPost<WebSourceEntry>(`/kb/${kbId}/web-sources`, payload);
}

/** GET /api/v1/kb/{kbId}/web-sources (M12-CONTRACTS.md section 3.4), most recently registered first. */
export function listWebSources(kbId: string, page = 1, size = 20): Promise<PageResult<WebSourceEntry>> {
  return apiGet<PageResult<WebSourceEntry>>(`/kb/${kbId}/web-sources`, { page, size });
}

/**
 * POST /api/v1/web-sources/{sourceId}/sync (M12-CONTRACTS.md section 3.4): one on-demand sync.
 * Never rejects for a fetch problem -- the outcome (SUCCESS/UNCHANGED/SKIPPED/FAILED) rides on
 * the returned entry's last_fetch_status, matching what the scheduled pass would have recorded.
 */
export function syncWebSource(sourceId: string): Promise<WebSourceEntry> {
  return apiPost<WebSourceEntry>(`/web-sources/${sourceId}/sync`);
}

/** PUT /api/v1/web-sources/{sourceId} (M12-CONTRACTS.md section 3.4): flips the scheduled-sync switch. */
export function updateWebSource(sourceId: string, syncEnabled: boolean): Promise<WebSourceEntry> {
  return apiPut<WebSourceEntry>(`/web-sources/${sourceId}`, { sync_enabled: syncEnabled });
}

/**
 * DELETE /api/v1/web-sources/{sourceId} (M12-CONTRACTS.md section 3.4): removes the registration
 * only -- the document it fed stays in the knowledge base (weak binding).
 */
export function removeWebSource(sourceId: string): Promise<void> {
  return apiDelete<void>(`/web-sources/${sourceId}`);
}

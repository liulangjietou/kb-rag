// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  ExtSource,
  ExtSourceItem,
  ExtSourceSyncAccepted,
  ExtSourceTestResult,
  PageResult,
  RegisterExtSourceRequest,
  UpdateExtSourceRequest,
} from './types';

/**
 * POST /api/v1/kb/{kbId}/ext-sources (M14/M23): registers one external source
 * and hands its first scan to the executor. Unlike the single-page web import, a bucket holds an
 * unbounded number of objects, so the request returns the registration row at once -- the first
 * scan's outcome appears on the row (and its item rows) once the scan finishes, watched by re-listing.
 */
export function registerExtSource(kbId: string, payload: RegisterExtSourceRequest): Promise<ExtSource> {
  return apiPost<ExtSource>(`/kb/${kbId}/ext-sources`, payload);
}

/** GET /api/v1/kb/{kbId}/ext-sources (M14 contract section 2.3), most recently registered first. */
export function listExtSources(kbId: string, page = 1, size = 20): Promise<PageResult<ExtSource>> {
  return apiGet<PageResult<ExtSource>>(`/kb/${kbId}/ext-sources`, { page, size });
}

/**
 * POST /api/v1/ext-sources/{sourceId}/sync (M14 contract section 2.3): accepts one on-demand scan.
 * The scan runs off the request thread, so this only acknowledges acceptance -- the outcome lands
 * on the source and item rows.
 */
export function syncExtSource(sourceId: string): Promise<ExtSourceSyncAccepted> {
  return apiPost<ExtSourceSyncAccepted>(`/ext-sources/${sourceId}/sync`);
}

/** GET /api/v1/ext-sources/{sourceId}/items (M14 contract section 2.3): per-object sync outcomes. */
export function listExtSourceItems(sourceId: string, page = 1, size = 20): Promise<PageResult<ExtSourceItem>> {
  return apiGet<PageResult<ExtSourceItem>>(`/ext-sources/${sourceId}/items`, { page, size });
}

/**
 * PUT /api/v1/ext-sources/{sourceId} (M14 contract section 2.3): edits connection details. A blank
 * or omitted secret_key keeps the stored credential (the read API always masks it).
 */
export function updateExtSource(sourceId: string, payload: UpdateExtSourceRequest): Promise<ExtSource> {
  return apiPut<ExtSource>(`/ext-sources/${sourceId}`, payload);
}

/**
 * POST /api/v1/ext-sources/{sourceId}/test (M14 contract section 2.3): probes connectivity and
 * credentials without touching any object. `up` is false with a detail string when the store did
 * not answer or the bucket does not exist.
 */
export function testExtSource(sourceId: string): Promise<ExtSourceTestResult> {
  return apiPost<ExtSourceTestResult>(`/ext-sources/${sourceId}/test`);
}

/**
 * DELETE /api/v1/ext-sources/{sourceId} (M14 contract section 2.3): removes the source and its item
 * rows only -- the documents they fed stay in the knowledge base (weak binding).
 */
export function removeExtSource(sourceId: string): Promise<void> {
  return apiDelete<void>(`/ext-sources/${sourceId}`);
}

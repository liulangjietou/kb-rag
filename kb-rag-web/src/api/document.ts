// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut, apiUpload } from './request';
import type {
  DocumentPreview,
  KbChunk,
  KbDocument,
  PageResult,
  ProcessStatus,
  ReparseDocumentRequest,
} from './types';

export interface ListDocumentsParams {
  process_status?: ProcessStatus;
  page?: number;
  /** 页大小，缺省由服务端取 DEFAULT_PAGE_SIZE（20）。 */
  size?: number;
}

export function listDocuments(kbId: string, params?: ListDocumentsParams): Promise<PageResult<KbDocument>> {
  return apiGet<PageResult<KbDocument>>(`/kb/${kbId}/documents`, params);
}

export function uploadDocument(kbId: string, file: File): Promise<KbDocument> {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<KbDocument>(`/kb/${kbId}/documents`, formData);
}

export function reindexDocument(docId: string): Promise<void> {
  return apiPost<void>(`/documents/${docId}/reindex`);
}

/**
 * DELETE /api/v1/documents/{docId} (M11-CONTRACTS.md section 2.2): moves the document into the
 * recycle bin. The URL predates M11 and kept its meaning of "delete this document", but the
 * deletion is now reversible via the trash tab until the retention period runs out or an explicit
 * purge follows.
 */
export function deleteDocument(docId: string): Promise<void> {
  return apiDelete<void>(`/documents/${docId}`);
}

export function listChunks(docId: string, page?: number): Promise<PageResult<KbChunk>> {
  return apiGet<PageResult<KbChunk>>(`/documents/${docId}/chunks`, { page });
}

/** GET /api/v1/documents/{docId}/preview (M3-CONTRACTS.md section 3.4). */
export function getDocumentPreview(docId: string): Promise<DocumentPreview> {
  return apiGet<DocumentPreview>(`/documents/${docId}/preview`);
}

/** POST /api/v1/documents/{docId}/confirm: proceeds a PENDING_CONFIRM document past the pause. */
export function confirmDocument(docId: string): Promise<void> {
  return apiPost<void>(`/documents/${docId}/confirm`);
}

/**
 * POST /api/v1/documents/{docId}/reparse (M3-CONTRACTS.md section 3.4).
 * ASSUMPTION: the contract does not spell out a response shape for "重解析"; assumed to return a
 * fresh DocumentPreview so the drawer can redisplay the effect of the overridden clean_rules
 * before the user confirms or tweaks the rules again.
 */
export function reparseDocument(docId: string, payload?: ReparseDocumentRequest): Promise<DocumentPreview> {
  return apiPost<DocumentPreview>(`/documents/${docId}/reparse`, payload);
}

// ---------------------------------------------------------------------------
// Content governance (M11-CONTRACTS.md section 2.2)
// ---------------------------------------------------------------------------

/** POST /api/v1/documents/{docId}/submit-review: DRAFT | REJECTED -> PENDING_REVIEW. */
export function submitDocumentReview(docId: string): Promise<KbDocument> {
  return apiPost<KbDocument>(`/documents/${docId}/submit-review`);
}

/** POST /api/v1/documents/{docId}/approve: PENDING_REVIEW -> PUBLISHED, clears review_note. */
export function approveDocument(docId: string): Promise<KbDocument> {
  return apiPost<KbDocument>(`/documents/${docId}/approve`);
}

/** POST /api/v1/documents/{docId}/reject: PENDING_REVIEW -> REJECTED with a mandatory note. */
export function rejectDocument(docId: string, note: string): Promise<KbDocument> {
  return apiPost<KbDocument>(`/documents/${docId}/reject`, { note });
}

/**
 * PUT /api/v1/documents/{docId}/validity: sets or clears the validity window. Null clears a bound,
 * and an expires_at in the past is allowed on purpose -- it is how "take this offline now" works.
 */
export function updateDocumentValidity(
  docId: string,
  effectiveAt: string | null,
  expiresAt: string | null,
): Promise<KbDocument> {
  return apiPut<KbDocument>(`/documents/${docId}/validity`, {
    effective_at: effectiveAt,
    expires_at: expiresAt,
  });
}

/** GET /api/v1/kb/{kbId}/trash: pages the recycle bin, most recently trashed first. */
export function listTrash(kbId: string, page?: number): Promise<PageResult<KbDocument>> {
  return apiGet<PageResult<KbDocument>>(`/kb/${kbId}/trash`, { page });
}

/** POST /api/v1/documents/{docId}/restore: instant flag flip back out of the recycle bin. */
export function restoreDocument(docId: string): Promise<KbDocument> {
  return apiPost<KbDocument>(`/documents/${docId}/restore`);
}

/**
 * DELETE /api/v1/documents/{docId}/purge: the irreversible removal, engine copies included. Only
 * reachable for a document already in the trash -- the two-step confirmation against typos.
 */
export function purgeDocument(docId: string): Promise<void> {
  return apiDelete<void>(`/documents/${docId}/purge`);
}

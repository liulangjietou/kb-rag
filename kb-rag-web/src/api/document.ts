import { apiGet, apiPost, apiUpload } from './request';
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

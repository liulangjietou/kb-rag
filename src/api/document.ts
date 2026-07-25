import { apiGet, apiPost, apiUpload } from './request';
import type { KbChunk, KbDocument, PageResult, ProcessStatus } from './types';

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

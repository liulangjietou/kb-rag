import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  ConfirmDocumentsRequest,
  CreateKbRequest,
  KnowledgeBase,
  RebuildRequest,
  UpdateIndexConfigRequest,
} from './types';

export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return apiGet<KnowledgeBase[]>('/kb');
}

export function getKnowledgeBase(kbId: string): Promise<KnowledgeBase> {
  return apiGet<KnowledgeBase>(`/kb/${kbId}`);
}

export function createKnowledgeBase(payload: CreateKbRequest): Promise<KnowledgeBase> {
  return apiPost<KnowledgeBase>('/kb', payload);
}

export function deleteKnowledgeBase(kbId: string): Promise<void> {
  return apiDelete<void>(`/kb/${kbId}`);
}

/** PUT /api/v1/kb/{kbId}/index-config (M2-CONTRACTS.md section 4). */
export function updateIndexConfig(kbId: string, payload: UpdateIndexConfigRequest): Promise<void> {
  return apiPut<void>(`/kb/${kbId}/index-config`, payload);
}

/**
 * POST /api/v1/kb/{kbId}/rebuild (M2-CONTRACTS.md section 4). Body omitted/empty means
 * "all config_stale documents". Progress is not exposed via a dedicated task-status endpoint
 * anywhere in M1/M2-CONTRACTS.md, so the caller tracks completion by re-polling the existing
 * document list and watching config_stale flip back to false (see KbDetailPage).
 */
export function rebuildKb(kbId: string, payload?: RebuildRequest): Promise<void> {
  return apiPost<void>(`/kb/${kbId}/rebuild`, payload);
}

/**
 * POST /api/v1/kb/{kbId}/documents/confirm (M3-CONTRACTS.md section 3.4): batch-confirms
 * PENDING_CONFIRM documents past the parse-preview pause. Omitted doc_ids = every PENDING_CONFIRM
 * document in this KB.
 */
export function confirmKbDocuments(kbId: string, payload?: ConfirmDocumentsRequest): Promise<void> {
  return apiPost<void>(`/kb/${kbId}/documents/confirm`, payload);
}

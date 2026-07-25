import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  CreateIkDictEntryRequest,
  IkDictEntry,
  IkDictStatus,
  IkDictType,
  ModelStatus,
  PageResult,
} from './types';

export function getModelStatus(): Promise<ModelStatus> {
  return apiGet<ModelStatus>('/system/model-status');
}

export interface ListIkDictParams {
  dict_type?: IkDictType;
  page?: number;
}

/** GET /api/v1/dict/ik (M2-CONTRACTS.md section 3). */
export function listIkDict(params?: ListIkDictParams): Promise<PageResult<IkDictEntry>> {
  return apiGet<PageResult<IkDictEntry>>('/dict/ik', params);
}

/** POST /api/v1/dict/ik (M2-CONTRACTS.md section 3). */
export function createIkDictEntry(payload: CreateIkDictEntryRequest): Promise<IkDictEntry> {
  return apiPost<IkDictEntry>('/dict/ik', payload);
}

/** DELETE /api/v1/dict/ik/{word} (M2-CONTRACTS.md section 3); word is the table's unique key. */
export function deleteIkDictEntry(word: string): Promise<void> {
  return apiDelete<void>(`/dict/ik/${encodeURIComponent(word)}`);
}

/**
 * ASSUMPTION / GAP: M2-CONTRACTS.md section 3 only lists GET/POST/DELETE for `/api/v1/dict/ik`,
 * but the M2 web deliverable (section 5) explicitly asks for an enable/disable toggle on each
 * entry. There is no contracted endpoint for this, so a PUT is assumed here by symmetry with
 * the index-config PUT elsewhere in the contract; this needs backend confirmation/addition.
 */
export function updateIkDictStatus(word: string, status: IkDictStatus): Promise<void> {
  return apiPut<void>(`/dict/ik/${encodeURIComponent(word)}`, { status });
}

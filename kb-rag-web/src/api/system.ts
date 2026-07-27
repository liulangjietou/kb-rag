// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  AlertConfig,
  CreateIkDictEntryRequest,
  DemoImportResult,
  DemoStatus,
  IkDictEntry,
  IkDictStatus,
  IkDictType,
  ModelStatus,
  PageResult,
  UpdateAlertConfigRequest,
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
 * PUT /api/v1/dict/ik/{word}: flips an entry between ENABLED and DISABLED. Verified against
 * IkDictController#updateStatus -- the route the M2 web deliverable's toggle needs does exist,
 * even though M2-CONTRACTS.md section 3 lists only GET/POST/DELETE.
 */
export function updateIkDictStatus(word: string, status: IkDictStatus): Promise<void> {
  return apiPut<void>(`/dict/ik/${encodeURIComponent(word)}`, { status });
}

/** GET /api/v1/system/alert-config (M3-CONTRACTS.md section 3.6). */
export function getAlertConfig(): Promise<AlertConfig> {
  return apiGet<AlertConfig>('/system/alert-config');
}

/** PUT /api/v1/system/alert-config. */
export function updateAlertConfig(payload: UpdateAlertConfigRequest): Promise<void> {
  return apiPut<void>('/system/alert-config', payload);
}

/** POST /api/v1/system/alert-config/test: sends a test message to the configured webhook. */
export function testAlertConfig(): Promise<void> {
  return apiPost<void>('/system/alert-config/test');
}

/** GET /api/v1/system/demo/status (M3-CONTRACTS.md section 3.7); drives the KB list empty-state button. */
export function getDemoStatus(): Promise<DemoStatus> {
  return apiGet<DemoStatus>('/system/demo/status');
}

/** POST /api/v1/system/demo/import; idempotent -- repeat calls return the existing Demo KB's kb_id. */
export function importDemo(): Promise<DemoImportResult> {
  return apiPost<DemoImportResult>('/system/demo/import');
}

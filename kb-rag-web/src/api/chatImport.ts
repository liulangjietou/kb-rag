// Author: owlzhangfq@gmail.com
import { apiPost, apiUpload } from './request';
import type { ChatImportPreviewResponse, ConfirmChatImportRequest } from './types';

/**
 * POST /api/v1/kb/{kbId}/chat-imports (M3-CONTRACTS.md section 3.5): multipart upload, returns a
 * session-match preview only -- nothing is persisted until chat-imports/confirm is called.
 *
 * The wire field name stays `mapping_profile` (unchanged since M3), but M8-CONTRACTS.md section
 * 0.7 redefines the value it carries: instead of a bare built-in profile name, callers now pass a
 * `t_kb_source_mapping.mapping_id` (or a built-in's bare name for old-value compatibility, e.g.
 * "memotrace"). The parameter here is named `mappingId` to reflect that new contract; the wizard
 * always sends a mapping_id selected from GET /source-mappings.
 */
export function previewChatImport(
  kbId: string,
  file: File,
  mappingId?: string,
): Promise<ChatImportPreviewResponse> {
  const formData = new FormData();
  formData.append('file', file);
  if (mappingId) {
    formData.append('mapping_profile', mappingId);
  }
  return apiUpload<ChatImportPreviewResponse>(`/kb/${kbId}/chat-imports`, formData);
}

/** POST /api/v1/kb/{kbId}/chat-imports/confirm: executes the import for the previewed sessions. */
export function confirmChatImport(kbId: string, payload: ConfirmChatImportRequest): Promise<void> {
  return apiPost<void>(`/kb/${kbId}/chat-imports/confirm`, payload);
}

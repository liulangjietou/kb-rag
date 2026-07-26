// Author: owlzhangfq@gmail.com
import { apiPost, apiUpload } from './request';
import type { ChatImportPreviewResponse, ConfirmChatImportRequest } from './types';

/**
 * POST /api/v1/kb/{kbId}/chat-imports (M3-CONTRACTS.md section 3.5): multipart upload, returns a
 * session-match preview only -- nothing is persisted until chat-imports/confirm is called.
 */
export function previewChatImport(
  kbId: string,
  file: File,
  mappingProfile?: string,
): Promise<ChatImportPreviewResponse> {
  const formData = new FormData();
  formData.append('file', file);
  if (mappingProfile) {
    formData.append('mapping_profile', mappingProfile);
  }
  return apiUpload<ChatImportPreviewResponse>(`/kb/${kbId}/chat-imports`, formData);
}

/** POST /api/v1/kb/{kbId}/chat-imports/confirm: executes the import for the previewed sessions. */
export function confirmChatImport(kbId: string, payload: ConfirmChatImportRequest): Promise<void> {
  return apiPost<void>(`/kb/${kbId}/chat-imports/confirm`, payload);
}

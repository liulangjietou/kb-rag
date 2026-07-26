// Author: owlzhangfq@gmail.com
import { apiGet, apiPost } from './request';
import type { ActivateImpact, ActivateVersionResponse, DocumentVersion } from './types';

/** GET /api/v1/documents/{docId}/versions (M4a-CONTRACTS.md section 1.2), newest first. */
export function listDocumentVersions(docId: string): Promise<DocumentVersion[]> {
  return apiGet<DocumentVersion[]>(`/documents/${docId}/versions`);
}

/**
 * GET /api/v1/documents/{docId}/versions/{versionId}/activate-impact (M4a-CONTRACTS.md
 * section 1.2): pre-flight check the web drawer calls before showing the activation confirm
 * dialog, so rollback_mode and stale_annotation_count can be surfaced up front.
 */
export function getActivateImpact(docId: string, versionId: string): Promise<ActivateImpact> {
  return apiGet<ActivateImpact>(`/documents/${docId}/versions/${versionId}/activate-impact`);
}

/**
 * POST /api/v1/documents/{docId}/versions/{versionId}/activate (M4a-CONTRACTS.md section 1.2).
 * INSTANT mode switches synchronously; REBUILD mode kicks off an async rebuild task and the
 * caller tracks completion by watching the owning document's process_status (see VersionDrawer).
 */
export function activateVersion(docId: string, versionId: string): Promise<ActivateVersionResponse> {
  return apiPost<ActivateVersionResponse>(`/documents/${docId}/versions/${versionId}/activate`);
}

// Author: owlzhangfq@gmail.com
import { apiGet, apiPost, apiPut } from './request';
import type {
  Annotation,
  EditChunkRequest,
  KbChunk,
  MergeChunksRequest,
  SplitChunkRequest,
  ToggleChunkRequest,
} from './types';

/**
 * PUT /api/v1/chunks/{chunkId} (M4a-CONTRACTS.md section 2.1): in-place content edit, re-embeds
 * and overwrites both search engines. Writes one t_kb_annotation row (annotation_type=EDIT).
 * ASSUMPTION: response shape is not spelled out by the contract; modelled as returning the
 * updated KbChunk (standard REST PUT semantics), matching every other row shape already typed.
 * The annotation workbench does not rely on this return value beyond success/failure -- it
 * reloads the current chunk page afterwards -- so the assumption carries no behavioral risk.
 */
export function editChunk(chunkId: string, payload: EditChunkRequest): Promise<KbChunk> {
  return apiPut<KbChunk>(`/chunks/${chunkId}`, payload);
}

/**
 * POST /api/v1/chunks/{chunkId}/toggle (M4a-CONTRACTS.md section 2.1): enable/disable only, no
 * re-embedding. Writes one t_kb_annotation row (annotation_type=TOGGLE).
 */
export function toggleChunk(chunkId: string, payload: ToggleChunkRequest): Promise<KbChunk> {
  return apiPost<KbChunk>(`/chunks/${chunkId}/toggle`, payload);
}

/**
 * POST /api/v1/chunks/merge (M4a-CONTRACTS.md section 2.1): merges 2+ same-document/same-version/
 * same-parent/seq-contiguous chunks into one new chunk; soft-deletes the sources. Writes one
 * t_kb_annotation row (annotation_type=MERGE). Cross-doc/cross-version, non-contiguous seq, or
 * fewer than 2 ids fail server-side with INVALID_PARAM.
 * ASSUMPTION: response shape not spelled out; modelled as the array of newly created chunk(s)
 * since a parent merge cascades into re-sliced children, but callers reload the chunk page rather
 * than trusting this value's exact shape.
 */
export function mergeChunks(payload: MergeChunksRequest): Promise<KbChunk[]> {
  return apiPost<KbChunk[]>('/chunks/merge', payload);
}

/**
 * POST /api/v1/chunks/{chunkId}/split (M4a-CONTRACTS.md section 2.1): splits one chunk into N+1
 * chunks at the given ascending in-bounds character offsets; soft-deletes the source. Writes one
 * t_kb_annotation row (annotation_type=SPLIT). Same response-shape caveat as mergeChunks.
 */
export function splitChunk(chunkId: string, payload: SplitChunkRequest): Promise<KbChunk[]> {
  return apiPost<KbChunk[]>(`/chunks/${chunkId}/split`, payload);
}

/**
 * GET /api/v1/documents/{docId}/annotations/pending-review (M4a-CONTRACTS.md section 2.3): the
 * old-version annotation list ("原文摘录、操作类型、是否已在新版本重做") surfaced by the version
 * management drawer's stale-annotation alert.
 */
export function listPendingReviewAnnotations(docId: string): Promise<Annotation[]> {
  return apiGet<Annotation[]>(`/documents/${docId}/annotations/pending-review`);
}

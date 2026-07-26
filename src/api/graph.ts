// Author: owlzhangfq@gmail.com
// GraphRAG admin API (M7-CONTRACTS.md section 0.10). See the doc comments on the corresponding
// types in ./types.ts for the per-endpoint assumptions declared against the section 0.10 blueprint.
import { apiGet, apiPost, apiPut } from './request';
import type {
  GraphEntity,
  GraphEntitySourceChunk,
  GraphSummary,
  ListGraphEntitiesParams,
  PageResult,
  TriggerGraphExtractResponse,
  UpdateGraphConfigRequest,
} from './types';

/** PUT /api/v1/kb/{kbId}/graph/config: toggles the knowledge base's GraphRAG switch. */
export function updateGraphConfig(kbId: string, payload: UpdateGraphConfigRequest): Promise<void> {
  return apiPut<void>(`/kb/${kbId}/graph/config`, payload);
}

/** POST /api/v1/kb/{kbId}/graph/extract: manually triggers a full re-extract over the active version's chunks. */
export function triggerGraphExtract(kbId: string): Promise<TriggerGraphExtractResponse> {
  return apiPost<TriggerGraphExtractResponse>(`/kb/${kbId}/graph/extract`);
}

/** GET /api/v1/kb/{kbId}/graph/summary: entity/relation/covered-chunk counts + latest task status. */
export function getGraphSummary(kbId: string): Promise<GraphSummary> {
  return apiGet<GraphSummary>(`/kb/${kbId}/graph/summary`);
}

/** GET /api/v1/kb/{kbId}/graph/entities?query=&page=: searchable, paginated entity list. */
export function listGraphEntities(kbId: string, params?: ListGraphEntitiesParams): Promise<PageResult<GraphEntity>> {
  return apiGet<PageResult<GraphEntity>>(`/kb/${kbId}/graph/entities`, params);
}

/** GET /api/v1/kb/{kbId}/graph/entities/{entityName}/chunks: source-chunk drill-down for one entity. */
export function getGraphEntityChunks(kbId: string, entityName: string): Promise<GraphEntitySourceChunk[]> {
  return apiGet<GraphEntitySourceChunk[]>(`/kb/${kbId}/graph/entities/${encodeURIComponent(entityName)}/chunks`);
}

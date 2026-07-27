// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  CopySourceMappingRequest,
  CreateSourceMappingRequest,
  SourceMapping,
  UpdateSourceMappingRequest,
} from './types';

/**
 * GET /api/v1/source-mappings (M8-CONTRACTS.md section 0.7).
 * ASSUMPTION: the contract does not mention pagination for this listing (unlike
 * `/dict/ik`'s PageResult), and the row count is inherently small -- a handful of built-in
 * templates seeded at startup plus whatever custom profiles an operator adds -- so this mirrors
 * listApiKeys' plain-array shape rather than PageResult<SourceMapping>.
 */
export function listSourceMappings(): Promise<SourceMapping[]> {
  return apiGet<SourceMapping[]>('/source-mappings');
}

/** POST /api/v1/source-mappings (M8-CONTRACTS.md section 0.7): create a custom mapping. */
export function createSourceMapping(payload: CreateSourceMappingRequest): Promise<SourceMapping> {
  return apiPost<SourceMapping>('/source-mappings', payload);
}

/** PUT /api/v1/source-mappings/{mappingId}; custom rows only -- built-ins are read-only ("不可改"). */
export function updateSourceMapping(mappingId: string, payload: UpdateSourceMappingRequest): Promise<SourceMapping> {
  return apiPut<SourceMapping>(`/source-mappings/${encodeURIComponent(mappingId)}`, payload);
}

/** DELETE /api/v1/source-mappings/{mappingId}; custom rows only -- built-ins cannot be deleted ("不可删"). */
export function deleteSourceMapping(mappingId: string): Promise<void> {
  return apiDelete<void>(`/source-mappings/${encodeURIComponent(mappingId)}`);
}

/**
 * POST /api/v1/source-mappings/{mappingId}/copy -- "复制为自定义" action on a built-in row.
 * See CopySourceMappingRequest's doc comment in api/types.ts for why this is an assumed route.
 */
export function copySourceMapping(mappingId: string, payload?: CopySourceMappingRequest): Promise<SourceMapping> {
  return apiPost<SourceMapping>(`/source-mappings/${encodeURIComponent(mappingId)}/copy`, payload);
}

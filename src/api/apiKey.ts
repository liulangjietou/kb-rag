// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type { ApiKey, ApiKeyWithSecret, CreateApiKeyRequest, UpdateApiKeyScopeRequest } from './types';

/**
 * API Key management endpoints (M4c-CONTRACTS.md section 3: "API Key 管理端点（管理鉴权）：创建
 * （返回明文一次）/列表(prefix+末4位)/禁用/轮换/删除，app_scope 配置"). Admin-JWT-authenticated,
 * hence routed through the shared client (request.ts) like every other management endpoint.
 */

/** POST /api/v1/api-keys: the only response that ever carries the plaintext secret. */
export function createApiKey(payload: CreateApiKeyRequest): Promise<ApiKeyWithSecret> {
  return apiPost<ApiKeyWithSecret>('/api-keys', payload);
}

/** GET /api/v1/api-keys. ASSUMPTION: modelled as a plain array, no paging named by the contract. */
export function listApiKeys(): Promise<ApiKey[]> {
  return apiGet<ApiKey[]>('/api-keys');
}

/**
 * ASSUMPTION: the contract only says "禁用" (disable); modelled as a symmetric status PUT (mirrors
 * system.ts's updateIkDictStatus assumption) so the same list-page Switch control can both disable
 * and re-enable a key without a separate endpoint pair.
 */
export function updateApiKeyStatus(keyId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> {
  return apiPut<void>(`/api-keys/${keyId}/status`, { status });
}

/** POST /api/v1/api-keys/{keyId}/rotate: issues a new secret, invalidating the old one immediately. */
export function rotateApiKey(keyId: string): Promise<ApiKeyWithSecret> {
  return apiPost<ApiKeyWithSecret>(`/api-keys/${keyId}/rotate`);
}

export function deleteApiKey(keyId: string): Promise<void> {
  return apiDelete<void>(`/api-keys/${keyId}`);
}

/** PUT /api/v1/api-keys/{keyId}/scope (ASSUMPTION, see UpdateApiKeyScopeRequest's doc comment in types.ts). */
export function updateApiKeyScope(keyId: string, payload: UpdateApiKeyScopeRequest): Promise<void> {
  return apiPut<void>(`/api-keys/${keyId}/scope`, payload);
}

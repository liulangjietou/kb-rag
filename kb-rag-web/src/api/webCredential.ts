// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  CreateWebCredentialRequest,
  UpdateWebCredentialRequest,
  WebCredentialEntry,
} from './types';

/**
 * GET /api/v1/web-credentials（M18）：全部站点凭据，新建在前。任何响应都不含 secret。
 */
export function listWebCredentials(): Promise<WebCredentialEntry[]> {
  return apiGet<WebCredentialEntry[]>('/web-credentials');
}

/** POST /api/v1/web-credentials（M18）：为一个 host 建凭据，host 全局唯一。 */
export function createWebCredential(payload: CreateWebCredentialRequest): Promise<WebCredentialEntry> {
  return apiPost<WebCredentialEntry>('/web-credentials', payload);
}

/**
 * PUT /api/v1/web-credentials/{credentialId}（M18）：只写传入的字段；secret 留空 = 不改密码，
 * 所以停启用、改用户名都不需要重新持有密码。
 */
export function updateWebCredential(
  credentialId: string,
  payload: UpdateWebCredentialRequest,
): Promise<WebCredentialEntry> {
  return apiPut<WebCredentialEntry>(`/web-credentials/${credentialId}`, payload);
}

/** DELETE /api/v1/web-credentials/{credentialId}（M18）：硬删，密码随行消失。 */
export function removeWebCredential(credentialId: string): Promise<void> {
  return apiDelete<void>(`/web-credentials/${credentialId}`);
}

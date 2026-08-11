// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  CreateWebCredentialRequest,
  UpdateWebCredentialRequest,
  WebCredentialEntry,
} from './types';

/**
 * GET /api/v1/web-credentials（M18）：本租户的站点凭据，新建在前。任何响应都不含 secret。
 * V22 起只回本租户的行——别的租户为同一 host 配的凭据既看不到也改不了。
 */
export function listWebCredentials(): Promise<WebCredentialEntry[]> {
  return apiGet<WebCredentialEntry[]>('/web-credentials');
}

/** POST /api/v1/web-credentials（M18）：为一个 host 建凭据，host 在本租户内唯一（V22 起）。 */
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

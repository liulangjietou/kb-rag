// Author: owlzhangfq@gmail.com
import { streamChat, type ChatStreamHandlers } from './chatStream';
import { getToken } from './authStorage';
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  AppVersion,
  BindGateDatasetRequest,
  ChatPreviewRequest,
  ChatResponse,
  CreateAppRequest,
  CreateAppVersionRequest,
  KbApp,
  UpdateAppRequest,
} from './types';

// ---------------------------------------------------------------------------
// Application CRUD (M4c-CONTRACTS.md section 2: "应用 CRUD `/api/v1/apps`")
// ---------------------------------------------------------------------------

export function listApps(): Promise<KbApp[]> {
  return apiGet<KbApp[]>('/apps');
}

export function getApp(appId: string): Promise<KbApp> {
  return apiGet<KbApp>(`/apps/${appId}`);
}

export function createApp(payload: CreateAppRequest): Promise<KbApp> {
  return apiPost<KbApp>('/apps', payload);
}

/** ASSUMPTION: see UpdateAppRequest's doc comment in types.ts. */
export function updateApp(appId: string, payload: UpdateAppRequest): Promise<KbApp> {
  return apiPut<KbApp>(`/apps/${appId}`, payload);
}

export function deleteApp(appId: string): Promise<void> {
  return apiDelete<void>(`/apps/${appId}`);
}

// ---------------------------------------------------------------------------
// App version / release gate (M4c-CONTRACTS.md section 2)
// ---------------------------------------------------------------------------

/** GET /api/v1/apps/{id}/versions, newest first. */
export function listAppVersions(appId: string): Promise<AppVersion[]> {
  return apiGet<AppVersion[]>(`/apps/${appId}/versions`);
}

/**
 * ASSUMPTION: section 2 does not name a single-version GET, but one is needed to poll gate
 * progress for exactly the version being watched (GATING -> terminal state) without re-fetching
 * and diffing the whole version list on every 3s tick.
 */
export function getAppVersion(appVersionId: string): Promise<AppVersion> {
  return apiGet<AppVersion>(`/app-versions/${appVersionId}`);
}

/** POST /api/v1/apps/{id}/versions (M4c-CONTRACTS.md section 2): snapshots the given config into a new DRAFT version. */
export function createAppVersion(appId: string, payload: CreateAppVersionRequest): Promise<AppVersion> {
  return apiPost<AppVersion>(`/apps/${appId}/versions`, payload);
}

/** POST /api/v1/app-versions/{vid}/submit-test (M4c-CONTRACTS.md section 2): DRAFT -> TESTING. */
export function submitAppVersionForTest(appVersionId: string): Promise<AppVersion> {
  return apiPost<AppVersion>(`/app-versions/${appVersionId}/submit-test`);
}

/**
 * POST /api/v1/app-versions/{vid}/release?force= (M4c-CONTRACTS.md section 2): runs the release
 * gate (double-run against the current RELEASED config when one exists) and, on pass, flips this
 * version to RELEASED while the prior RELEASED version becomes SUPERSEDED. force=true is required
 * only to push a GATE_LOG_ONLY version through, and is audited server-side ("留痕放行").
 */
export function releaseAppVersion(appVersionId: string, force?: boolean): Promise<AppVersion> {
  return apiPost<AppVersion>(`/app-versions/${appVersionId}/release${force ? '?force=true' : ''}`);
}

/** POST /api/v1/app-versions/{vid}/rollback (M4c-CONTRACTS.md section 2): re-releases a historical RELEASED/SUPERSEDED version. */
export function rollbackAppVersion(appVersionId: string): Promise<AppVersion> {
  return apiPost<AppVersion>(`/app-versions/${appVersionId}/rollback`);
}

/** PUT /api/v1/app-versions/{vid}/gate-dataset (ASSUMPTION, see BindGateDatasetRequest's doc comment). */
export function bindGateDataset(appVersionId: string, payload: BindGateDatasetRequest): Promise<AppVersion> {
  return apiPut<AppVersion>(`/app-versions/${appVersionId}/gate-dataset`, payload);
}

// ---------------------------------------------------------------------------
// Admin-authenticated chat preview (M4c-CONTRACTS.md section 4: "问答调试页...接入管理端内部 chat
// 预览，可直接复用对外 chat 逻辑经管理鉴权路径")
// ---------------------------------------------------------------------------

/**
 * ASSUMPTION: neither section 2 nor 3 names an internal preview endpoint -- the external
 * `/api/v1/knowledge/chat` is API-Key-gated by its own filter chain (section 3: "独立过滤器链"),
 * so it cannot double as the admin-auth preview path. Modelled as a same-shape sibling endpoint
 * under the admin-auth `/apps` resource, keyed by app_id with an optional app_version override
 * (mirrors the external contract's own app_id + optional app_version so both call sites share one
 * request type, see ChatPreviewRequest).
 */
export function chatPreview(appId: string, payload: ChatPreviewRequest): Promise<ChatResponse> {
  return apiPost<ChatResponse>(`/apps/${appId}/chat-preview`, { ...payload, stream: false });
}

/** Streaming counterpart of chatPreview; uses the admin JWT (read directly, bypassing the shared axios client -- see chatStream.ts). */
export function streamChatPreview(appId: string, payload: ChatPreviewRequest, handlers: ChatStreamHandlers): Promise<void> {
  const token = getToken();
  const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {};
  return streamChat(`/api/v1/apps/${appId}/chat-preview`, headers, { ...payload, app_id: appId }, handlers);
}

import { getToken, SESSION_HEADER } from './authStorage';
import { apiDelete, apiGet } from './request';

export type ResourceVisitKind = 'KB' | 'APP';

export interface ResourceVisit {
  resource_type: ResourceVisitKind;
  resource_id: string;
  name: string;
  visited_at: string;
}

const PATH = '/me/resource-visits';
const RECORD_TIMEOUT_MS = 5_000;

export function listResourceVisits(): Promise<ResourceVisit[]> {
  return apiGet<ResourceVisit[]>(PATH);
}

export function clearResourceVisits(): Promise<void> {
  return apiDelete<void>(PATH);
}

/** 辅助访问记录失败不打断已打开的业务页面，不自动重发。 */
export async function recordResourceVisit(kind: ResourceVisitKind, resourceId: string): Promise<void> {
  const token = getToken();
  if (!token) return;
  const response = await fetch(`/api/v1${PATH}`, {
    method: 'POST',
    signal: AbortSignal.timeout(RECORD_TIMEOUT_MS),
    headers: { 'Content-Type': 'application/json', [SESSION_HEADER]: token },
    body: JSON.stringify({ resource_type: kind, resource_id: resourceId }),
  });
  if (!response.ok) throw new Error('Resource visit could not be recorded');
}

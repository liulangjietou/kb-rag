// Author: owlzhangfq@gmail.com
// Tenant administration client (M16-CONTRACTS.md section 3), all of it behind tenant:manage
// server-side. The list is unpaged on purpose: tenants are an operator-curated handful, not data.
import { apiGet, apiPost, apiPut } from './request';
import type { SaveTenantRequest, TenantStatus, TenantSummary } from './types';

export function listTenants(): Promise<TenantSummary[]> {
  return apiGet<TenantSummary[]>('/tenants');
}

export function createTenant(payload: SaveTenantRequest): Promise<TenantSummary> {
  return apiPost<TenantSummary>('/tenants', payload);
}

/** Renames only -- the server ignores everything but `name`, code being fixed at creation. */
export function renameTenant(tenantId: string, payload: SaveTenantRequest): Promise<TenantSummary> {
  return apiPut<TenantSummary>(`/tenants/${tenantId}`, payload);
}

/** Disabling blocks every login of the tenant's accounts; existing rows stay untouched. */
export function updateTenantStatus(tenantId: string, status: TenantStatus): Promise<void> {
  return apiPut<void>(`/tenants/${tenantId}/status`, { status });
}

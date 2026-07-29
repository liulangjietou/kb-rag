// Author: owlzhangfq@gmail.com
// Role administration client: permission grants plus knowledge base data scope.
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type { PermissionCatalogueItem, RoleSummary, SaveRoleRequest } from './types';

/**
 * GET /roles. Readable with role:manage or user:manage -- granting a role means picking one from a
 * list, so the account screen needs this too.
 */
export function listRoles(): Promise<RoleSummary[]> {
  return apiGet<RoleSummary[]>('/roles');
}

/** The catalogue the editor renders as a checkbox grid, already in display order per module. */
export function listPermissionCatalogue(): Promise<PermissionCatalogueItem[]> {
  return apiGet<PermissionCatalogueItem[]>('/roles/permissions');
}

export function createRole(payload: SaveRoleRequest): Promise<RoleSummary> {
  return apiPost<RoleSummary>('/roles', payload);
}

/** Replaces grants and scope wholesale; the role's code is not editable and is ignored here. */
export function updateRole(roleId: string, payload: SaveRoleRequest): Promise<RoleSummary> {
  return apiPut<RoleSummary>(`/roles/${roleId}`, payload);
}

/** Rejected server-side while any account still holds the role, and for built-in roles. */
export function deleteRole(roleId: string): Promise<void> {
  return apiDelete<void>(`/roles/${roleId}`);
}

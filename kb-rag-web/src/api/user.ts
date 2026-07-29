// Author: owlzhangfq@gmail.com
// Console user administration client, all of it behind user:manage server-side.
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  CreateUserRequest,
  PageResult,
  UpdateUserRequest,
  UserSummary,
} from './types';

export interface ListUsersParams {
  keyword?: string;
  status?: string;
  source?: string;
  page?: number;
  size?: number;
}

export function listUsers(params: ListUsersParams): Promise<PageResult<UserSummary>> {
  return apiGet<PageResult<UserSummary>>('/users', params);
}

/** Single-account read; unlike the list it carries role_ids, which is what the edit form binds to. */
export function getUser(userId: string): Promise<UserSummary> {
  return apiGet<UserSummary>(`/users/${userId}`);
}

export function createUser(payload: CreateUserRequest): Promise<UserSummary> {
  return apiPost<UserSummary>('/users', payload);
}

export function updateUser(userId: string, payload: UpdateUserRequest): Promise<UserSummary> {
  return apiPut<UserSummary>(`/users/${userId}`, payload);
}

/** Suspending an account also ends its live sessions server-side. */
export function updateUserStatus(userId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> {
  return apiPut<void>(`/users/${userId}/status`, { status });
}

/** Replaces the whole role set: whatever is not in the list is revoked. */
export function assignUserRoles(userId: string, roleIds: string[]): Promise<void> {
  return apiPut<void>(`/users/${userId}/roles`, { role_ids: roleIds });
}

/**
 * Resets a local password. The account is forced to rotate it at its next login, so the value typed
 * here is a handover secret rather than a password the operator picks on somebody's behalf.
 */
export function resetUserPassword(userId: string, newPassword: string): Promise<void> {
  return apiPost<void>(`/users/${userId}/reset-password`, { new_password: newPassword });
}

export function deleteUser(userId: string): Promise<void> {
  return apiDelete<void>(`/users/${userId}`);
}

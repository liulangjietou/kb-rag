// Author: owlzhangfq@gmail.com
import { apiGet, apiPost } from './request';
import type { ChangePasswordRequest, CurrentUser, LoginRequest, LoginResponse } from './types';

export function login(payload: LoginRequest): Promise<LoginResponse> {
  return apiPost<LoginResponse>('/auth/login', payload);
}

export function changePassword(payload: ChangePasswordRequest): Promise<void> {
  return apiPost<void>('/auth/change-password', payload);
}

export function getCurrentUser(): Promise<CurrentUser> {
  return apiGet<CurrentUser>('/auth/me');
}

/**
 * POST /api/v1/auth/logout: revokes the current token server-side (TokenStore.revoke). Dropping
 * the token locally is not enough on its own -- an unrevoked JWT stays valid until it expires, so
 * a copy captured from storage would keep working after the operator "logged out".
 *
 * Must be called while the token is still in storage: the request interceptor reads it from there
 * to build the Authorization header.
 */
export function logout(): Promise<void> {
  return apiPost<void>('/auth/logout');
}

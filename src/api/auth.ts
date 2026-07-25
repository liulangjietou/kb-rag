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

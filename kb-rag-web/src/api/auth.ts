// Author: owlzhangfq@gmail.com
import { apiGet, apiPost } from './request';
import type {
  ChangePasswordRequest,
  CurrentUser,
  LoginRequest,
  LoginResponse,
  SsoAvailability,
  SsoProviders,
} from './types';

export function login(payload: LoginRequest): Promise<LoginResponse> {
  return apiPost<LoginResponse>('/auth/login', payload);
}

/**
 * GET /api/v1/auth/sso-available: whether a corporate directory is wired into this deployment.
 *
 * Unauthenticated on purpose -- the login page has to render before anybody has a session. A failure
 * here is treated as "no directory" by the caller: offering a tab that cannot work is worse than not
 * offering it, and the local form is always available as the way in.
 */
export function getSsoAvailability(): Promise<SsoAvailability> {
  return apiGet<SsoAvailability>('/auth/sso-available');
}

/**
 * GET /api/v1/auth/sso/providers: which browser SSO protocols (OIDC/SAML/CAS) are configured
 * (M16-CONTRACTS.md section 5). Unauthenticated for the same reason as sso-available, and a
 * failure is likewise treated as "none configured" by the login page.
 */
export function getSsoProviders(): Promise<SsoProviders> {
  return apiGet<SsoProviders>('/auth/sso/providers');
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

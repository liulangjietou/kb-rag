// Thin wrapper around localStorage for the auth token, kept in one place so the
// storage key is not duplicated as a magic string across the codebase.

const TOKEN_STORAGE_KEY = 'kb-rag-web:auth-token';

/**
 * Request header carrying the console session token. Must match `sa-token.token-name`
 * on the server.
 *
 * Deliberately not `Authorization`: that header belongs to the open APIs, whose credential is an
 * API Key or a Memory Key. Keeping the console session on its own header means the three
 * credentials can never be mistaken for one another, and a custom header is also what keeps
 * cross-site request forgery out of the picture -- a browser will not attach it for an attacker.
 */
export const SESSION_HEADER = 'satoken';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_STORAGE_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
}

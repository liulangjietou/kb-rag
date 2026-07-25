// Thin wrapper around localStorage for the auth token, kept in one place so the
// storage key is not duplicated as a magic string across the codebase.

const TOKEN_STORAGE_KEY = 'kb-rag-web:auth-token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_STORAGE_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
}

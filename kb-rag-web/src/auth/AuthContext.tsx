// Author: owlzhangfq@gmail.com
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { getCurrentUser, logout as revokeSession } from '../api/auth';
import { clearToken, getToken, setToken as persistToken } from '../api/authStorage';
import type { CurrentUser } from '../api/types';

interface AuthState {
  token: string | null;
  user: CurrentUser | null;
  mustChangePassword: boolean;
  /** True while an existing token from localStorage is being validated against /auth/me. */
  initializing: boolean;
}

interface AuthContextValue extends AuthState {
  username: string | null;
  /** Display label for the header: the directory's name when there is one, else the login name. */
  displayName: string | null;
  /** Flattened permission codes of the session; empty until /auth/me has answered. */
  permissions: string[];
  /** True when the session sees every knowledge base. */
  kbScopeAll: boolean;
  /** Knowledge bases in scope; only meaningful while kbScopeAll is false. */
  kbIds: string[];
  loginSuccess: (token: string, mustChangePassword: boolean) => void;
  passwordChanged: () => void;
  logout: () => void;
  /** Whether the session holds one code. */
  can: (code: string) => boolean;
  /** Whether the session holds at least one of several codes, matching the server's "any of" rule. */
  canAny: (codes: string[]) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const EMPTY_STATE: AuthState = {
  token: null,
  user: null,
  mustChangePassword: false,
  initializing: false,
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    token: getToken(),
    user: null,
    mustChangePassword: false,
    initializing: true,
  });

  // On app start, an existing token must be re-validated: it tells us the current account, the
  // permissions of the session and whether a forced password change is still pending after a refresh.
  useEffect(() => {
    const token = getToken();
    if (!token) {
      setState((prev) => ({ ...prev, initializing: false }));
      return;
    }
    getCurrentUser()
      .then((user) => {
        setState({
          token,
          user,
          mustChangePassword: user.must_change_password,
          initializing: false,
        });
      })
      .catch(() => {
        // Invalid/expired token: the response interceptor already cleared storage on 401.
        setState(EMPTY_STATE);
      });
  }, []);

  const loginSuccess = useCallback((token: string, mustChangePassword: boolean) => {
    persistToken(token);
    // Hydration is marked as initializing, not left to run in the background: /auth/login returns a
    // token and nothing else, and the screen the session lands on is chosen from its permissions. A
    // router that rendered before /auth/me answered would read "no permissions" and park a perfectly
    // entitled account on the no-access page. The guard's spinner covers the round trip instead.
    setState({ token, user: null, mustChangePassword, initializing: true });
    getCurrentUser()
      .then((user) => setState((prev) => ({ ...prev, user, initializing: false })))
      .catch(() => setState((prev) => ({ ...prev, initializing: false })));
  }, []);

  const passwordChanged = useCallback(() => {
    setState((prev) => ({ ...prev, mustChangePassword: false }));
  }, []);

  const logout = useCallback(() => {
    // Revoke server-side first, while the token is still in storage for the interceptor to attach.
    // Local state is dropped regardless of the outcome: a failed revoke must not strand the
    // operator in a session they asked to leave, and the interceptor already surfaced the error.
    if (getToken()) {
      revokeSession().catch(() => undefined);
    }
    clearToken();
    setState(EMPTY_STATE);
  }, []);

  // A Set rather than repeated Array#includes: the menu and every guarded button ask on each render.
  const granted = useMemo(() => new Set(state.user?.permissions ?? []), [state.user]);

  const can = useCallback((code: string) => granted.has(code), [granted]);
  const canAny = useCallback(
    (codes: string[]) => codes.some((code) => granted.has(code)),
    [granted],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      ...state,
      username: state.user?.username ?? null,
      displayName: state.user?.display_name ?? state.user?.username ?? null,
      permissions: state.user?.permissions ?? [],
      kbScopeAll: state.user?.kb_scope_all ?? false,
      kbIds: state.user?.kb_ids ?? [],
      loginSuccess,
      passwordChanged,
      logout,
      can,
      canAny,
    }),
    [state, loginSuccess, passwordChanged, logout, can, canAny],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return ctx;
}

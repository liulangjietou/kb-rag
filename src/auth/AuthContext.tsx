// Author: owlzhangfq@gmail.com
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { getCurrentUser, logout as revokeSession } from '../api/auth';
import { clearToken, getToken, setToken as persistToken } from '../api/authStorage';

interface AuthState {
  token: string | null;
  username: string | null;
  mustChangePassword: boolean;
  /** True while an existing token from localStorage is being validated against /auth/me. */
  initializing: boolean;
}

interface AuthContextValue extends AuthState {
  loginSuccess: (token: string, mustChangePassword: boolean) => void;
  passwordChanged: () => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    token: getToken(),
    username: null,
    mustChangePassword: false,
    initializing: true,
  });

  // On app start, an existing token must be re-validated: it tells us the current
  // username and whether a forced password change is still pending after a refresh.
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
          username: user.username,
          mustChangePassword: user.must_change_password,
          initializing: false,
        });
      })
      .catch(() => {
        // Invalid/expired token: the response interceptor already cleared storage on 401.
        setState({ token: null, username: null, mustChangePassword: false, initializing: false });
      });
  }, []);

  const loginSuccess = useCallback((token: string, mustChangePassword: boolean) => {
    persistToken(token);
    setState({ token, username: null, mustChangePassword, initializing: false });
    // /auth/login does not return the username; hydrate it lazily for the header display.
    getCurrentUser()
      .then((user) => setState((prev) => ({ ...prev, username: user.username })))
      .catch(() => undefined);
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
    setState({ token: null, username: null, mustChangePassword: false, initializing: false });
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ ...state, loginSuccess, passwordChanged, logout }),
    [state, loginSuccess, passwordChanged, logout],
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

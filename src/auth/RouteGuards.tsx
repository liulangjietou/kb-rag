import { Spin } from 'antd';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';

/** Blocks unauthenticated access to everything nested under it, redirecting to /login. */
export function RequireAuth() {
  const { token, initializing } = useAuth();
  const location = useLocation();

  if (initializing) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <Spin size="large" />
      </div>
    );
  }
  if (!token) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}

/** Forces a first-login admin straight to the change-password page (D: must_change_password). */
export function RequirePasswordChanged() {
  const { mustChangePassword } = useAuth();
  if (mustChangePassword) {
    return <Navigate to="/change-password" replace />;
  }
  return <Outlet />;
}

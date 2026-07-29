import { Result, Spin } from 'antd';
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

interface RequirePermissionProps {
  /** Any one of these admits the route, matching the server's @RequiresPermission semantics. */
  anyOf: string[];
}

/**
 * Gates a route on function permissions.
 *
 * Denial renders in place rather than redirecting: the account did ask for this screen, and bouncing it
 * to its landing page would leave a URL somebody was sent silently swallowed. It also keeps this guard
 * from fighting the root redirect, which picks its target from the same permission set.
 *
 * The server checks again on every call behind these screens. Hiding a route only spares the operator a
 * page that would answer 403; it is not what keeps the data safe.
 */
export function RequirePermission({ anyOf }: RequirePermissionProps) {
  const { canAny } = useAuth();
  if (!canAny(anyOf)) {
    return (
      <Result
        status="403"
        title="403"
        subTitle="当前账号没有访问该页面的权限，如需开通请联系管理员。"
      />
    );
  }
  return <Outlet />;
}

// Author: owlzhangfq@gmail.com
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { PERMISSIONS } from '../auth/permissions';
import { RequireAuth, RequirePasswordChanged, RequirePermission } from '../auth/RouteGuards';
import { ModelStatusProvider } from '../context/ModelStatusContext';
import MainLayout from '../layout/MainLayout';
import { NO_ACCESS_PATH, landingPath } from '../layout/navigation';
import ChangePasswordPage from '../pages/ChangePasswordPage';
import HomePage from '../pages/HomePage';
import LoginPage from '../pages/LoginPage';
import NoAccessPage from '../pages/NoAccessPage';
import RegisterPage from '../pages/RegisterPage';
import AppDetailPage from '../pages/apps/AppDetailPage';
import AppListPage from '../pages/apps/AppListPage';
import ChatDebugPage from '../pages/chat/ChatDebugPage';
import EvalCenterPage from '../pages/eval/EvalCenterPage';
import KbDetailPage from '../pages/kb/KbDetailPage';
import KbListPage from '../pages/kb/KbListPage';
import McpDebugPage from '../pages/mcp/McpDebugPage';
import MemoryLibraryDetailPage from '../pages/memory/MemoryLibraryDetailPage';
import MemoryLibraryListPage from '../pages/memory/MemoryLibraryListPage';
import SearchPage from '../pages/search/SearchPage';
import OperationAuditPage from '../pages/settings/OperationAuditPage';
import RegistrationReviewPage from '../pages/settings/RegistrationReviewPage';
import RoleManagePage from '../pages/settings/RoleManagePage';
import SettingsPage from '../pages/settings/SettingsPage';
import TenantManagePage from '../pages/settings/TenantManagePage';
import UserManagePage from '../pages/settings/UserManagePage';

/** Wraps the authenticated app shell with the model-status fetch (needs a valid token). */
function AuthenticatedShell() {
  return (
    <ModelStatusProvider>
      <MainLayout />
    </ModelStatusProvider>
  );
}

/** 未指定页面的会话进入登录后首页；深链接不会经过此处。 */
function LandingRedirect() {
  const { canAny } = useAuth();
  return <Navigate to={landingPath(canAny)} replace />;
}

export default function AppRouter() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route element={<RequireAuth />}>
        <Route path="/change-password" element={<ChangePasswordPage />} />

        <Route element={<RequirePasswordChanged />}>
          <Route path={NO_ACCESS_PATH} element={<NoAccessPage />} />

          <Route element={<AuthenticatedShell />}>
            <Route path="/home" element={<HomePage />} />
            <Route element={<RequirePermission anyOf={[PERMISSIONS.KB_READ]} />}>
              <Route path="/kb" element={<KbListPage />} />
              <Route path="/kb/:kbId" element={<KbDetailPage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.SEARCH_DEBUG]} />}>
              <Route path="/search" element={<SearchPage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.APP_READ, PERMISSIONS.SEARCH_DEBUG]} />}>
              <Route path="/chat" element={<ChatDebugPage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.APP_READ]} />}>
              <Route path="/apps" element={<AppListPage />} />
              <Route path="/apps/:appId" element={<AppDetailPage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.MEMORY_READ]} />}>
              <Route path="/memory" element={<MemoryLibraryListPage />} />
              <Route path="/memory/:libraryId" element={<MemoryLibraryDetailPage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.APP_READ, PERMISSIONS.MEMORY_READ]} />}>
              <Route path="/mcp" element={<McpDebugPage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.EVAL_READ]} />}>
              <Route path="/eval" element={<EvalCenterPage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.USER_MANAGE]} />}>
              <Route path="/users" element={<UserManagePage />} />
              <Route element={<RequirePermission anyOf={[PERMISSIONS.TENANT_MANAGE]} />}>
                <Route path="/users/registration-reviews" element={<RegistrationReviewPage />} />
              </Route>
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.ROLE_MANAGE]} />}>
              <Route path="/roles" element={<RoleManagePage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.TENANT_MANAGE]} />}>
              <Route path="/settings/tenants" element={<TenantManagePage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.AUDIT_READ]} />}>
              <Route path="/settings/operation-audits" element={<OperationAuditPage />} />
            </Route>
            <Route element={<RequirePermission anyOf={[PERMISSIONS.SYSTEM_CONFIG]} />}>
              <Route path="/settings" element={<SettingsPage />} />
            </Route>
          </Route>

          <Route path="/" element={<LandingRedirect />} />
          <Route path="*" element={<LandingRedirect />} />
        </Route>
      </Route>
    </Routes>
  );
}

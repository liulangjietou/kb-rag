import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth, RequirePasswordChanged } from '../auth/RouteGuards';
import { ModelStatusProvider } from '../context/ModelStatusContext';
import MainLayout from '../layout/MainLayout';
import ChangePasswordPage from '../pages/ChangePasswordPage';
import LoginPage from '../pages/LoginPage';
import KbDetailPage from '../pages/kb/KbDetailPage';
import KbListPage from '../pages/kb/KbListPage';
import SearchPage from '../pages/search/SearchPage';
import SettingsPage from '../pages/settings/SettingsPage';

/** Wraps the authenticated app shell with the model-status fetch (needs a valid token). */
function AuthenticatedShell() {
  return (
    <ModelStatusProvider>
      <MainLayout />
    </ModelStatusProvider>
  );
}

export default function AppRouter() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<RequireAuth />}>
        <Route path="/change-password" element={<ChangePasswordPage />} />

        <Route element={<RequirePasswordChanged />}>
          <Route element={<AuthenticatedShell />}>
            <Route path="/kb" element={<KbListPage />} />
            <Route path="/kb/:kbId" element={<KbDetailPage />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="/" element={<Navigate to="/kb" replace />} />
      <Route path="*" element={<Navigate to="/kb" replace />} />
    </Routes>
  );
}

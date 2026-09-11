// @vitest-environment jsdom
import { App as AntApp } from 'antd';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider } from './AuthContext';
import { RequireAuth, RequirePasswordChanged, RequirePermission } from './RouteGuards';
import LoginPage from '../pages/LoginPage';
import ChangePasswordPage from '../pages/ChangePasswordPage';

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
  changePassword: vi.fn(),
}));

vi.mock('../api/auth', () => ({
  ...mocks,
  getSsoAvailability: () => Promise.resolve({ sso_available: false }),
  getSsoProviders: () => Promise.resolve({ oidc: false, saml: false, cas: false }),
  logout: () => Promise.resolve(),
}));
vi.mock('../components/AuthShell', () => ({ default: ({ children }: { children: ReactNode }) => <main>{children}</main> }));
vi.mock('../components/LoginSliderCaptcha', () => ({
  default: ({ onVerified }: { onVerified: (proof: string) => void }) =>
    <button type="button" onClick={() => onVerified('test-only-proof')}>测试验证通过</button>,
}));

const destination = '/workspace?app=app_fixture&conversation=conv_fixture#latest';

function CurrentAddress() {
  const location = useLocation();
  return <output aria-label="当前位置">{location.pathname}{location.search}{location.hash}</output>;
}

function renderFlow(initial: string, mustChangePassword = false, permissions = ['app:use']) {
  mocks.login.mockResolvedValue({ token: 'test-only-token', must_change_password: mustChangePassword });
  mocks.getCurrentUser.mockResolvedValue({ username: 'test-user', permissions, must_change_password: mustChangePassword });
  return render(<MemoryRouter initialEntries={[initial]}><AntApp><AuthProvider><Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route element={<RequireAuth />}>
      <Route path="/change-password" element={<ChangePasswordPage />} />
      <Route element={<RequirePasswordChanged />}>
        <Route path="/" element={<Navigate to="/home" replace />} />
        <Route path="/home" element={<CurrentAddress />} />
        <Route element={<RequirePermission anyOf={['app:use']} />}>
          <Route path="/workspace" element={<CurrentAddress />} />
        </Route>
        <Route element={<RequirePermission anyOf={['kb:read']} />}>
          <Route path="/kb" element={<CurrentAddress />} />
        </Route>
      </Route>
    </Route>
  </Routes></AuthProvider></AntApp></MemoryRouter>);
}

async function submitLogin() {
  fireEvent.change(await screen.findByPlaceholderText('输入邮箱或平台用户名'), { target: { value: 'test-user' } });
  fireEvent.change(screen.getByPlaceholderText('输入平台密码'), { target: { value: 'fixture-password' } });
  fireEvent.click(screen.getByRole('button', { name: '测试验证通过' }));
}

async function submitNewPassword() {
  fireEvent.change(await screen.findByPlaceholderText('输入当前密码'), { target: { value: 'fixture-password' } });
  fireEvent.change(screen.getByPlaceholderText('输入新密码'), { target: { value: 'fixture-new-password' } });
  fireEvent.change(screen.getByPlaceholderText('再次输入新密码'), { target: { value: 'fixture-new-password' } });
  fireEvent.click(screen.getByRole('button', { name: '更新密码并进入' }));
}

beforeEach(() => {
  localStorage.clear();
  mocks.changePassword.mockResolvedValue(undefined);
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: (media: string) => ({
    matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
  }) });
});
afterEach(() => { cleanup(); vi.resetAllMocks(); localStorage.clear(); });

describe('认证后恢复原页面', () => {
  it('员工登录后保留原会话的应用、会话和锚点', async () => {
    renderFlow(destination);
    await submitLogin();
    expect((await screen.findByLabelText('当前位置')).textContent).toBe(destination);
    expect(mocks.login).toHaveBeenCalledTimes(1);
  });

  it('首次登录需要改密时，完成改密仍回到原会话', async () => {
    renderFlow(destination, true);
    await submitLogin();
    await submitNewPassword();
    expect((await screen.findByLabelText('当前位置')).textContent).toBe(destination);
    expect(mocks.changePassword).toHaveBeenCalledTimes(1);
  });

  it('已有登录态被强制改密时保留正在访问的会话', async () => {
    localStorage.setItem('kb-rag-web:auth-token', 'test-only-token');
    renderFlow(destination, true);
    await submitNewPassword();
    expect((await screen.findByLabelText('当前位置')).textContent).toBe(destination);
  });

  it('直接进入改密页时回到首页，最小员工不被送到无权限知识库', async () => {
    localStorage.setItem('kb-rag-web:auth-token', 'test-only-token');
    renderFlow('/change-password', true);
    await submitNewPassword();
    expect((await screen.findByLabelText('当前位置')).textContent).toBe('/home');
  });

  it('回到原页面仍经过权限校验', async () => {
    renderFlow(destination, false, ['kb:read']);
    await submitLogin();
    expect(await screen.findByText('当前账号没有访问该页面的权限，如需开通请联系管理员。')).toBeTruthy();
    expect(screen.queryByLabelText('当前位置')).toBeNull();
  });
});

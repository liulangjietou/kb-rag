// @vitest-environment jsdom
// Author: owlzhangfq@gmail.com
import { App as AntApp } from 'antd';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import LoginPage from './LoginPage';
import { LOGIN_METHOD_KEY } from '../utils/loginMethodPreference';

const mocks = vi.hoisted(() => ({
  clearLoginMemory: vi.fn(),
  getSsoAvailability: vi.fn(),
  getSsoProviders: vi.fn(),
  loadLoginMemory: vi.fn(),
  login: vi.fn(),
  loginSuccess: vi.fn(),
  saveLoginMemory: vi.fn(),
  storePasswordCredential: vi.fn(),
}));

vi.mock('../api/auth', () => ({
  getSsoAvailability: mocks.getSsoAvailability,
  getSsoProviders: mocks.getSsoProviders,
  login: mocks.login,
}));

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ loginSuccess: mocks.loginSuccess }),
}));

vi.mock('../components/AuthShell', () => ({
  default: ({ children }: { children: ReactNode }) => <main>{children}</main>,
}));

vi.mock('../components/LoginSliderCaptcha', () => ({
  default: ({
    disabled,
    onVerified,
    resetKey,
  }: {
    disabled?: boolean;
    onVerified: (proof: string) => void;
    resetKey: number;
  }) => (
    <div>
      <span data-testid="captcha-reset-key">{resetKey}</span>
      <button
        type="button"
        disabled={disabled}
        onClick={() => {
          onVerified('proof-1');
          onVerified('proof-1');
        }}
      >
        完成滑块
      </button>
    </div>
  ),
}));

vi.mock('../utils/loginMemory', () => ({
  clearLoginMemory: mocks.clearLoginMemory,
  loadLoginMemory: mocks.loadLoginMemory,
  saveLoginMemory: mocks.saveLoginMemory,
  storePasswordCredential: mocks.storePasswordCredential,
}));

function renderLoginPage() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <AntApp>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<div>登录成功</div>} />
          <Route path="/change-password" element={<div>修改密码</div>} />
        </Routes>
      </AntApp>
    </MemoryRouter>,
  );
}

function setNativeValue(input: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  setter?.call(input, value);
}

beforeEach(() => {
  window.localStorage.clear();
  mocks.getSsoAvailability.mockResolvedValue({ sso_available: false });
  mocks.getSsoProviders.mockResolvedValue({ oidc: false, saml: false, cas: false });
  mocks.loadLoginMemory.mockReturnValue({ remember: false, usernames: {} });
  mocks.storePasswordCredential.mockResolvedValue(undefined);
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
  class ResizeObserverMock {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.defineProperty(window, 'ResizeObserver', { configurable: true, value: ResizeObserverMock });
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, value: ResizeObserverMock });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('LoginPage slider integration', () => {
  it('proof 写入同步 ref 后通过原生 requestSubmit 自动登录且只请求一次', async () => {
    let resolveLogin: ((value: { token: string; must_change_password: boolean }) => void) | undefined;
    mocks.login.mockReturnValue(new Promise((resolve) => {
      resolveLogin = resolve;
    }));
    renderLoginPage();
    const username = await screen.findByPlaceholderText('输入邮箱或平台用户名') as HTMLInputElement;
    const password = screen.getByPlaceholderText('输入平台密码') as HTMLInputElement;
    expect(screen.getByRole('link', { name: '使用工作邮箱注册' }).getAttribute('href')).toBe('/register');
    expect(username.maxLength).toBe(254);
    await waitFor(() => expect(mocks.getSsoAvailability).toHaveBeenCalledOnce());

    // 模拟密码管理器只改原生 DOM 值、没有触发 React change。
    setNativeValue(username, 'richard');
    setNativeValue(password, 'correct-password');
    fireEvent.click(screen.getByRole('button', { name: '完成滑块' }));

    await waitFor(() => expect(mocks.login).toHaveBeenCalledTimes(1));
    expect(mocks.login).toHaveBeenCalledWith({
      username: 'richard',
      password: 'correct-password',
      mode: 'LOCAL',
      captcha_proof: 'proof-1',
    });

    await act(async () => {
      resolveLogin?.({ token: 'token-1', must_change_password: false });
    });
    await screen.findByText('登录成功');
    expect(mocks.loginSuccess).toHaveBeenCalledWith('token-1', false);
    expect(mocks.clearLoginMemory).toHaveBeenCalled();
    expect(mocks.saveLoginMemory).not.toHaveBeenCalled();
    expect(mocks.storePasswordCredential).not.toHaveBeenCalled();
    expect(window.localStorage.getItem(LOGIN_METHOD_KEY)).toBe('LOCAL');
  });

  it('登录失败后刷新 challenge，同时保留用户名和密码', async () => {
    mocks.login.mockRejectedValue(new Error('UNAUTHORIZED'));
    renderLoginPage();
    const username = await screen.findByPlaceholderText('输入邮箱或平台用户名') as HTMLInputElement;
    const password = screen.getByPlaceholderText('输入平台密码') as HTMLInputElement;
    fireEvent.change(username, { target: { value: 'richard' } });
    fireEvent.change(password, { target: { value: 'wrong-password' } });
    fireEvent.click(screen.getByRole('button', { name: '完成滑块' }));

    await waitFor(() => expect(screen.getByTestId('captcha-reset-key').textContent).toBe('1'));
    expect(username.value).toBe('richard');
    expect(password.value).toBe('wrong-password');
    expect(mocks.login).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(LOGIN_METHOD_KEY)).toBeNull();
  });

  it('仅在勾选且登录成功后调用浏览器密码管理器', async () => {
    mocks.login.mockResolvedValue({ token: 'token-2', must_change_password: true });
    renderLoginPage();
    const username = await screen.findByPlaceholderText('输入邮箱或平台用户名');
    const password = screen.getByPlaceholderText('输入平台密码');
    fireEvent.change(username, { target: { value: 'remembered-user' } });
    fireEvent.change(password, { target: { value: 'browser-owned-secret' } });
    fireEvent.click(screen.getByRole('checkbox', { name: '记住用户名和密码' }));
    fireEvent.click(screen.getByRole('button', { name: '完成滑块' }));

    await waitFor(() => expect(mocks.storePasswordCredential).toHaveBeenCalledWith(
      'remembered-user',
      'browser-owned-secret',
    ));
    expect(mocks.saveLoginMemory).toHaveBeenLastCalledWith({
      remember: true,
      usernames: { LOCAL: 'remembered-user' },
    });
    await screen.findByText('修改密码');
  });

  it('登录响应返回前取消记住时不恢复已撤回的凭据保存授权', async () => {
    let resolveLogin: ((value: { token: string; must_change_password: boolean }) => void) | undefined;
    mocks.login.mockReturnValue(new Promise((resolve) => {
      resolveLogin = resolve;
    }));
    renderLoginPage();
    fireEvent.change(await screen.findByPlaceholderText('输入邮箱或平台用户名'), {
      target: { value: 'shared-browser-user' },
    });
    fireEvent.change(screen.getByPlaceholderText('输入平台密码'), {
      target: { value: 'must-not-be-saved' },
    });
    const remember = screen.getByRole('checkbox', { name: '记住用户名和密码' });
    fireEvent.click(remember);
    fireEvent.click(screen.getByRole('button', { name: '完成滑块' }));
    await waitFor(() => expect(mocks.login).toHaveBeenCalledOnce());

    fireEvent.click(remember);
    await act(async () => {
      resolveLogin?.({ token: 'token-revoked', must_change_password: false });
    });

    await screen.findByText('登录成功');
    expect(mocks.saveLoginMemory).toHaveBeenCalledTimes(1);
    expect(mocks.clearLoginMemory).toHaveBeenCalledTimes(2);
    expect(mocks.storePasswordCredential).not.toHaveBeenCalled();
  });

  it('SSO 探测延迟返回时不打断已经开始的本地自动登录', async () => {
    let resolveAvailability: ((value: { sso_available: boolean }) => void) | undefined;
    let resolveLogin: ((value: { token: string; must_change_password: boolean }) => void) | undefined;
    mocks.getSsoAvailability.mockReturnValue(new Promise((resolve) => {
      resolveAvailability = resolve;
    }));
    mocks.login.mockReturnValue(new Promise((resolve) => {
      resolveLogin = resolve;
    }));
    renderLoginPage();
    const username = await screen.findByPlaceholderText('输入邮箱或平台用户名') as HTMLInputElement;
    const password = screen.getByPlaceholderText('输入平台密码') as HTMLInputElement;
    fireEvent.change(username, { target: { value: 'richard' } });
    fireEvent.change(password, { target: { value: 'correct-password' } });
    fireEvent.click(screen.getByRole('button', { name: '完成滑块' }));
    await waitFor(() => expect(mocks.login).toHaveBeenCalledOnce());

    await act(async () => {
      resolveAvailability?.({ sso_available: true });
    });

    expect(screen.getByPlaceholderText('输入邮箱或平台用户名')).toBe(username);
    expect(username.value).toBe('richard');
    expect(password.value).toBe('correct-password');
    expect(mocks.login).toHaveBeenCalledWith(expect.objectContaining({ mode: 'LOCAL' }));

    await act(async () => {
      resolveLogin?.({ token: 'token-3', must_change_password: false });
    });
    await screen.findByText('登录成功');
  });
});

describe('LoginPage 登录方式恢复', () => {
  it('勾选记住时保留密码管理器自动填入的值，直到认证成功才保存密码', async () => {
    renderLoginPage();
    const username = await screen.findByPlaceholderText('输入邮箱或平台用户名') as HTMLInputElement;
    const password = screen.getByPlaceholderText('输入平台密码') as HTMLInputElement;
    await waitFor(() => expect(mocks.getSsoProviders).toHaveBeenCalledOnce());
    setNativeValue(username, 'autofilled-user');
    setNativeValue(password, 'browser-secret');
    fireEvent.click(screen.getByRole('checkbox', { name: '记住用户名和密码' }));
    expect(username.value).toBe('autofilled-user');
    expect(password.value).toBe('browser-secret');
    expect(mocks.storePasswordCredential).not.toHaveBeenCalled();
  });

  it('上次成功的目录方式在配置允许时恢复，缓存的平台用户名不覆盖它', async () => {
    window.localStorage.setItem(LOGIN_METHOD_KEY, 'SSO');
    mocks.getSsoAvailability.mockResolvedValue({ sso_available: true });
    mocks.loadLoginMemory.mockReturnValue({ remember: true, usernames: { LOCAL: 'local-user', SSO: 'directory-user' } });
    mocks.login.mockResolvedValue({ token: 'directory-token', must_change_password: false });
    renderLoginPage();
    const username = await screen.findByPlaceholderText('输入域账号') as HTMLInputElement;
    expect(username.value).toBe('directory-user');
    fireEvent.change(screen.getByPlaceholderText('输入域账号密码'), { target: { value: 'directory-password' } });
    fireEvent.click(screen.getByRole('button', { name: '完成滑块' }));
    await screen.findByText('登录成功');
    expect(mocks.login).toHaveBeenCalledWith(expect.objectContaining({ mode: 'SSO' }));
    expect(window.localStorage.getItem(LOGIN_METHOD_KEY)).toBe('SSO');
  });

  it('目录已关闭时回到平台，不沿用目录的用户名或密码', async () => {
    window.localStorage.setItem(LOGIN_METHOD_KEY, 'SSO');
    mocks.loadLoginMemory.mockReturnValue({ remember: true, usernames: { SSO: 'directory-user' } });
    renderLoginPage();
    const username = await screen.findByPlaceholderText('输入邮箱或平台用户名') as HTMLInputElement;
    await waitFor(() => expect(mocks.getSsoAvailability).toHaveBeenCalledOnce());
    expect(username.value).toBe('');
    expect(screen.queryByRole('tab', { name: '域账号' })).toBeNull();
  });

  it('目录探测晚于浏览器自动填充时，保留新凭据与当前方式', async () => {
    window.localStorage.setItem(LOGIN_METHOD_KEY, 'SSO');
    let resolveAvailability: ((value: { sso_available: boolean }) => void) | undefined;
    mocks.getSsoAvailability.mockReturnValue(new Promise((resolve) => { resolveAvailability = resolve; }));
    renderLoginPage();
    const username = await screen.findByPlaceholderText('输入邮箱或平台用户名') as HTMLInputElement;
    const password = screen.getByPlaceholderText('输入平台密码') as HTMLInputElement;
    setNativeValue(username, 'autofilled-user');
    setNativeValue(password, 'browser-secret');
    await act(async () => { resolveAvailability?.({ sso_available: true }); });
    expect(screen.getByPlaceholderText('输入邮箱或平台用户名')).toBe(username);
    expect(username.value).toBe('autofilled-user');
    expect(password.value).toBe('browser-secret');
  });

  it('手动切换方式会清空密码和验证状态，但不会写入成功偏好', async () => {
    window.localStorage.setItem(LOGIN_METHOD_KEY, 'LOCAL');
    mocks.getSsoAvailability.mockResolvedValue({ sso_available: true });
    renderLoginPage();
    const directoryTab = await screen.findByRole('tab', { name: '域账号' });
    fireEvent.change(screen.getByPlaceholderText('输入邮箱或平台用户名'), { target: { value: 'local-user' } });
    fireEvent.change(screen.getByPlaceholderText('输入平台密码'), { target: { value: 'secret' } });
    fireEvent.click(directoryTab);
    expect((screen.getByPlaceholderText('输入域账号密码') as HTMLInputElement).value).toBe('');
    expect(screen.getByTestId('captcha-reset-key').textContent).toBe('1');
    expect(window.localStorage.getItem(LOGIN_METHOD_KEY)).toBe('LOCAL');
    fireEvent.click(screen.getByRole('tab', { name: '平台账号' }));
    expect((screen.getByPlaceholderText('输入邮箱或平台用户名') as HTMLInputElement).value).toBe('local-user');
    expect(mocks.login).not.toHaveBeenCalled();
  });
});

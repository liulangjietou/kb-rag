// @vitest-environment jsdom
import { AxiosError, AxiosHeaders, type AxiosAdapter } from 'axios';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './AuthContext';
import client, { apiGet } from '../api/request';
import { getToken, SESSION_HEADER, setToken } from '../api/authStorage';

const messageError = vi.hoisted(() => vi.fn());
vi.mock('antd', () => ({ message: { error: messageError } }));

const originalAdapter = client.defaults.adapter;
const sessionToken = 'logout-test-session';
const user = {
  username: 'logout-test-user',
  permissions: ['app:use'],
  must_change_password: false,
};

beforeEach(() => {
  localStorage.clear();
  messageError.mockReset();
  window.history.replaceState(null, '', '/login');
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  client.defaults.adapter = originalAdapter;
});

describe('退出登录的真实请求链路', () => {
  it('本地立即退出时，注销请求仍携带原凭证，后续匿名请求不复用凭证', async () => {
    const requests: Array<{ url?: string; token: unknown }> = [];
    const adapter: AxiosAdapter = async (config) => {
      requests.push({ url: config.url, token: config.headers.get(SESSION_HEADER) });
      const response = {
        data: { code: 'OK', data: config.url === '/auth/me' ? user : null },
        status: 200,
        statusText: 'OK',
        headers: new AxiosHeaders(),
        config,
      };
      if (config.url === '/auth/logout' && config.headers.get(SESSION_HEADER) !== sessionToken) {
        throw new AxiosError('Request failed with status code 401', AxiosError.ERR_BAD_REQUEST,
          config, undefined, {
            ...response,
            status: 401,
            data: { code: 'UNAUTHORIZED', message: 'missing bearer token', data: null },
          });
      }
      return response;
    };
    client.defaults.adapter = adapter;
    setToken(sessionToken);
    const { result } = renderHook(useAuth, { wrapper: AuthProvider });
    await waitFor(() => expect(result.current.username).toBe(user.username));

    await act(async () => {
      result.current.logout();
      expect(getToken()).toBeNull();
    });
    await apiGet('/auth/sso-available');

    expect(result.current.token).toBeNull();
    expect(result.current.user).toBeNull();
    expect(result.current.permissions).toEqual([]);
    expect(requests).toEqual([
      { url: '/auth/me', token: sessionToken },
      { url: '/auth/logout', token: sessionToken },
      { url: '/auth/sso-available', token: undefined },
    ]);
    expect(messageError).not.toHaveBeenCalled();
  });

  it('注销接口网络失败时仍清理本地会话，并保留真实错误提示', async () => {
    client.defaults.adapter = async (config) => {
      if (config.url === '/auth/logout') {
        throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config);
      }
      return { data: { code: 'OK', data: user }, status: 200, statusText: 'OK',
        headers: new AxiosHeaders(), config };
    };
    setToken(sessionToken);
    const { result } = renderHook(useAuth, { wrapper: AuthProvider });
    await waitFor(() => expect(result.current.username).toBe(user.username));

    await act(async () => result.current.logout());

    expect(getToken()).toBeNull();
    expect(result.current.token).toBeNull();
    expect(result.current.user).toBeNull();
    expect(messageError).toHaveBeenCalledWith('Network Error');
  });

  it('没有会话时重复退出不发送注销请求', async () => {
    const adapter = vi.fn<AxiosAdapter>();
    client.defaults.adapter = adapter;
    const { result } = renderHook(useAuth, { wrapper: AuthProvider });

    await act(async () => result.current.logout());

    expect(result.current.token).toBeNull();
    expect(adapter).not.toHaveBeenCalled();
    expect(messageError).not.toHaveBeenCalled();
  });
});

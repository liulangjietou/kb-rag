// @vitest-environment jsdom
import { AxiosError, AxiosHeaders, type AxiosAdapter } from 'axios';
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResult } from './types';

const mocks = vi.hoisted(() => ({
  clearToken: vi.fn(),
  messageError: vi.fn(),
}));

vi.mock('antd', () => ({
  message: { error: mocks.messageError },
}));

vi.mock('./authStorage', () => ({
  clearToken: mocks.clearToken,
  getToken: vi.fn(() => null),
}));

import client, { apiPost } from './request';

const originalAdapter = client.defaults.adapter;

beforeEach(() => {
  mocks.clearToken.mockReset();
  mocks.messageError.mockReset();
});

afterAll(() => {
  client.defaults.adapter = originalAdapter;
});

describe('request error presentation', () => {
  it('错误密码的 401 在登录页展示后端信息且不触发会话过期跳转', async () => {
    const adapter: AxiosAdapter = async (config) => {
      const data: ApiResult<unknown> = {
        code: 'UNAUTHORIZED',
        message: 'invalid username or password',
        data: null,
        request_id: 'req-login-1',
      };
      throw new AxiosError<ApiResult<unknown>>(
        'Request failed with status code 401',
        AxiosError.ERR_BAD_REQUEST,
        config,
        undefined,
        {
          data,
          status: 401,
          statusText: 'Unauthorized',
          headers: new AxiosHeaders(),
          config,
        },
      );
    };
    client.defaults.adapter = adapter;
    window.history.replaceState(null, '', '/login');

    await expect(apiPost('/auth/login', { username: 'richard' })).rejects.toBeInstanceOf(AxiosError);

    expect(mocks.messageError).toHaveBeenCalledWith(
      'invalid username or password (request_id: req-login-1)',
    );
    expect(mocks.clearToken).not.toHaveBeenCalled();
    expect(window.location.pathname).toBe('/login');
  });
});

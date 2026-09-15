// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { recordResourceVisit } from './resourceVisit';
import { SESSION_HEADER, setToken, clearToken, getToken } from './authStorage';
const fetchMock = vi.fn();
beforeEach(() => { clearToken(); fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock); });
afterEach(() => { clearToken(); vi.unstubAllGlobals(); });
it('无会话不记录，记录请求只携带当前会话及资源标识', async () => {
  await recordResourceVisit('KB', 'kb_one');
  expect(fetchMock).not.toHaveBeenCalled();
  setToken('synthetic-session');
  fetchMock.mockResolvedValue({ ok: true });
  await recordResourceVisit('APP', 'app_one');
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/v1/me/resource-visits', expect.objectContaining({
    method: 'POST', headers: { 'Content-Type': 'application/json', [SESSION_HEADER]: 'synthetic-session' },
    body: JSON.stringify({ resource_type: 'APP', resource_id: 'app_one' }),
  }));
});
it('辅助请求失败不删除会话或跳转登录，交由调用方忽略且不重发', async () => {
  setToken('synthetic-session');
  fetchMock.mockResolvedValue({ ok: false, status: 503 });
  await expect(recordResourceVisit('KB', 'kb_one')).rejects.toThrow('Resource visit could not be recorded');
  expect(fetchMock).toHaveBeenCalledOnce();
  expect(getToken()).toBe('synthetic-session');
  expect(location.pathname).toBe('/');
});

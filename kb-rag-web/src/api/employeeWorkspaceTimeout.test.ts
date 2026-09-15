// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { subscribeEmployeeRun } from './employeeWorkspace';

vi.mock('./authStorage', () => ({ getToken: () => 'fixture-session', clearToken: vi.fn(), SESSION_HEADER: 'satoken' }));
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('员工订阅的连接期限', () => {
  it('连接黑洞超过服务端订阅期限后释放读取，可重新连接但不发起模型命令', async () => {
    vi.useFakeTimers();
    const navigation = new AbortController();
    let connection!: AbortSignal;
    const fetch = vi.fn().mockImplementation((_url, options) => {
      connection = options.signal;
      return new Promise((_resolve, reject) => connection.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))));
    });
    vi.stubGlobal('fetch', fetch);
    const execution = subscribeEmployeeRun('app', 'conv', 'run', vi.fn(), navigation.signal).catch((error: unknown) => error);
    try {
      await vi.advanceTimersByTimeAsync(135_000);
      expect(connection.aborted).toBe(true);
      expect(await execution).toMatchObject({ code: 'STREAM_TIMEOUT', retryable: true });
      expect(fetch).toHaveBeenCalledTimes(1);
      expect(fetch.mock.calls[0][1].method).toBe('GET');
    } finally { navigation.abort(); await execution; }
  });

  it('已经收到响应头但正文永不结束时，同样释放连接', async () => {
    vi.useFakeTimers();
    const navigation = new AbortController();
    let connection!: AbortSignal;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url, options) => {
      connection = options.signal;
      return new Response(new ReadableStream({ start(controller) {
        connection.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')));
      } }), { headers: { 'Content-Type': 'text/event-stream' } });
    }));
    const execution = subscribeEmployeeRun('app', 'conv', 'run', vi.fn(), navigation.signal).catch((error: unknown) => error);
    try {
      await vi.advanceTimersByTimeAsync(135_000);
      expect(connection.aborted).toBe(true);
      expect(await execution).toMatchObject({ code: 'STREAM_TIMEOUT', retryable: true });
    } finally { navigation.abort(); await execution; }
  });
});

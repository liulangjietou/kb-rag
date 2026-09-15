// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { employeeWorkspace, EmployeeApiError, subscribeEmployeeRun, type EmployeeRun } from './employeeWorkspace';

vi.mock('./authStorage', () => ({ getToken: () => 'fixture-session', clearToken: vi.fn(), SESSION_HEADER: 'satoken' }));

const run: EmployeeRun = {
  run_id: 'run', conversation_id: 'conv', turn_no: 1, question: '问题', answer: '片段', references: [],
  status: 'RUNNING', stage: 'GENERATING', app_version_id: 'av', app_version: 'v1', snapshot_bound: true,
  revision: 2, checkpoint_seq: 1, degraded: false, restricted: false, created_at: '2026-09-11T10:00:00',
};
const frame = (event: string, data: unknown) => `event: ${event}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`;
const sse = (body: string) => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
afterEach(() => vi.unstubAllGlobals());

describe('员工运行订阅', () => {
  it('只 GET 相同运行并复用控制台认证，终态快照可以在 done 丢失时证明落库', async () => {
    const fetch = vi.fn().mockResolvedValue(sse(frame('snapshot', run) + frame('snapshot', { ...run, status: 'SUCCEEDED', revision: 3 })));
    vi.stubGlobal('fetch', fetch);
    const received: EmployeeRun[] = [];
    await subscribeEmployeeRun('app', 'conv', 'run', (snapshot) => received.push(snapshot), new AbortController().signal);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/workspace/apps/app/conversations/conv/runs/run/events');
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'GET', cache: 'no-store', headers: { satoken: 'fixture-session' } });
    expect(received.map((item) => item.status)).toEqual(['RUNNING', 'SUCCEEDED']);
  });

  it('无终态 EOF 标记为可重连，不自动提交或重新生成', async () => {
    const fetch = vi.fn().mockResolvedValue(sse(frame('snapshot', run)));
    vi.stubGlobal('fetch', fetch);
    const receive = vi.fn();
    await expect(subscribeEmployeeRun('app', 'conv', 'run', receive, new AbortController().signal))
      .rejects.toMatchObject({ code: 'STREAM_INCOMPLETE', retryable: true });
    expect(receive).toHaveBeenCalledWith(run);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('同序号撤权仍传递清空后的视图', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sse(frame('snapshot', run)
      + frame('snapshot', { ...run, restricted: true, answer: '', references: [] })
      + frame('snapshot', { ...run, restricted: true, answer: '', references: [], status: 'FAILED' }))));
    const received: EmployeeRun[] = [];
    await subscribeEmployeeRun('app', 'conv', 'run', (snapshot) => received.push(snapshot), new AbortController().signal);
    expect(received[1]).toMatchObject({ restricted: true, answer: '', revision: 2 });
  });

  it('拒绝其他会话或运行的快照', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sse(frame('snapshot', { ...run, run_id: 'other' }))));
    const receive = vi.fn();
    await expect(subscribeEmployeeRun('app', 'conv', 'run', receive, new AbortController().signal))
      .rejects.toMatchObject({ code: 'INVALID_SNAPSHOT' });
    expect(receive).not.toHaveBeenCalled();
  });

  it('订阅撤权错误要求清理内容，不当作模型完成', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sse(frame('error', {
      code: 'FORBIDDEN', message: '访问已变化', retryable: false, clear_content: true,
    }))));
    await expect(subscribeEmployeeRun('app', 'conv', 'run', vi.fn(), new AbortController().signal))
      .rejects.toMatchObject({ code: 'FORBIDDEN', retryable: false, clearContent: true });
  });

  it('导航取消只中止 reader，不能发送停止命令', async () => {
    const abort = new AbortController();
    const fetch = vi.fn().mockImplementation(async () => {
      abort.abort();
      throw new DOMException('Aborted', 'AbortError');
    });
    vi.stubGlobal('fetch', fetch);
    await expect(subscribeEmployeeRun('app', 'conv', 'run', vi.fn(), abort.signal)).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][1].method).toBe('GET');
  });

  it('HTTP 权限错误保留清理语义', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'FORBIDDEN', message: '无权访问' }), { status: 403 })));
    await expect(employeeWorkspace.history('app', 'conv')).rejects.toMatchObject({ clearContent: true, retryable: false });
  });

  it('提交保持原始幂等标识和问题，网络异常不自动重发', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('offline'));
    vi.stubGlobal('fetch', fetch);
    await expect(employeeWorkspace.submit('app', 'conv', 'request_1', ' 原问题 ')).rejects.toBeInstanceOf(EmployeeApiError);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ request_id: 'request_1', query: ' 原问题 ' });
  });
});

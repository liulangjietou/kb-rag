// @vitest-environment jsdom
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { employeeWorkspace, EmployeeApiError, subscribeEmployeeRun, type EmployeeConversation, type EmployeeRun } from '../../api/employeeWorkspace';
import { useEmployeeConversation } from './useEmployeeConversation';

vi.mock('../../api/employeeWorkspace', async (original) => {
  const module = await original<typeof import('../../api/employeeWorkspace')>();
  return { ...module, employeeWorkspace: { conversation: vi.fn(), history: vi.fn(), submit: vi.fn(), stop: vi.fn(), rename: vi.fn() },
    subscribeEmployeeRun: vi.fn() };
});

const conversation: EmployeeConversation = { conversation_id: 'conv', app_id: 'app', title: '售后政策', last_turn: 1,
  active_run_id: 'run', last_activity_at: '2026-09-11T10:00:00', created_at: '2026-09-11T10:00:00' };
const run: EmployeeRun = { run_id: 'run', conversation_id: 'conv', turn_no: 1, question: '如何退货？', answer: '已保存的片段',
  references: [], status: 'RUNNING', stage: 'GENERATING', app_version_id: 'v1', app_version: 'v1.0', snapshot_bound: true,
  revision: 2, checkpoint_seq: 1, degraded: false, restricted: false, created_at: '2026-09-11T10:00:00' };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(employeeWorkspace.conversation).mockResolvedValue(conversation);
  vi.mocked(employeeWorkspace.history).mockResolvedValue([run]);
  vi.mocked(subscribeEmployeeRun).mockImplementation((_app, _conversation, _run, _snapshot, signal) =>
    new Promise((resolve) => { signal.addEventListener('abort', () => resolve(), { once: true }); }));
});
afterEach(cleanup);

describe('员工会话的异步响应顺序', () => {
  it('终态快照之后迟到的历史读取不能恢复停止按钮或重复订阅', async () => {
    const { result } = renderHook(() => useEmployeeConversation('app', 'conv', vi.fn()));
    await waitFor(() => expect(result.current.loading).toBe(false));
    const staleSummary = deferred<EmployeeConversation>();
    const staleHistory = deferred<EmployeeRun[]>();
    vi.mocked(employeeWorkspace.conversation).mockReturnValueOnce(staleSummary.promise);
    vi.mocked(employeeWorkspace.history).mockReturnValueOnce(staleHistory.promise);
    let refresh!: Promise<void>;
    act(() => { refresh = result.current.refresh(true); });
    const snapshot = vi.mocked(subscribeEmployeeRun).mock.calls.at(-1)![3];
    act(() => snapshot({ ...run, status: 'SUCCEEDED', stage: 'FINISHED', revision: 4, answer: '最终已保存的回答' }));
    await act(async () => { staleSummary.resolve(conversation); staleHistory.resolve([run]); await refresh; });
    expect(result.current.runs[0].status).toBe('SUCCEEDED');
    expect(result.current.conversation?.active_run_id).toBeNull();
  });

  it('读取中的旧空历史不能抹掉刚接受的问题和活动运行', async () => {
    vi.mocked(employeeWorkspace.conversation).mockResolvedValue({ ...conversation, last_turn: 0, active_run_id: null });
    vi.mocked(employeeWorkspace.history).mockResolvedValue([]);
    const { result } = renderHook(() => useEmployeeConversation('app', 'conv', vi.fn()));
    await waitFor(() => expect(result.current.loading).toBe(false));
    const staleSummary = deferred<EmployeeConversation>();
    const staleHistory = deferred<EmployeeRun[]>();
    vi.mocked(employeeWorkspace.conversation).mockReturnValueOnce(staleSummary.promise);
    vi.mocked(employeeWorkspace.history).mockReturnValueOnce(staleHistory.promise);
    let refresh!: Promise<void>;
    act(() => { refresh = result.current.refresh(true); });
    vi.mocked(employeeWorkspace.submit).mockResolvedValue(run);
    await act(async () => { await result.current.submit(run.question); });
    await act(async () => {
      staleSummary.resolve({ ...conversation, last_turn: 0, active_run_id: null }); staleHistory.resolve([]); await refresh;
    });
    expect(result.current.runs.map((item) => item.run_id)).toEqual(['run']);
    expect(result.current.conversation?.active_run_id).toBe('run');
    expect(result.current.conversation?.last_turn).toBe(1);
  });

  it('撤权已确认时，同一事件批次中的旧订阅快照不能恢复正文', async () => {
    const changed = vi.fn();
    const { result } = renderHook(() => useEmployeeConversation('app', 'conv', changed));
    await waitFor(() => expect(result.current.loading).toBe(false));
    const snapshot = vi.mocked(subscribeEmployeeRun).mock.calls.at(-1)![3];
    vi.mocked(employeeWorkspace.conversation).mockRejectedValueOnce(new EmployeeApiError('FORBIDDEN', '当前无权限', false, true));
    await act(async () => {
      await result.current.refresh(true);
      snapshot({ ...run, revision: 4, answer: '撤权前读取的旧正文' });
    });
    expect(result.current.authorizationError?.code).toBe('FORBIDDEN');
    expect(result.current.runs).toEqual([]);
  });

  it('撤权之前发起的历史请求不能重新启用内容，需要一次新的授权读取', async () => {
    const changed = vi.fn();
    const { result } = renderHook(() => useEmployeeConversation('app', 'conv', changed));
    await waitFor(() => expect(result.current.loading).toBe(false));
    const staleSummary = deferred<EmployeeConversation>();
    vi.mocked(employeeWorkspace.conversation).mockReturnValueOnce(staleSummary.promise);
    let refresh!: Promise<void>;
    act(() => { refresh = result.current.refresh(true); });
    act(() => result.current.authorizeFailure(new EmployeeApiError('FORBIDDEN', '无权限', false, true)));
    await act(async () => { staleSummary.resolve(conversation); await refresh; });
    expect(result.current.runs).toEqual([]);
    expect(result.current.authorizationError?.code).toBe('FORBIDDEN');
    await act(async () => { await result.current.refresh(); });
    expect(result.current.authorizationError).toBeUndefined();
    expect(result.current.runs[0].answer).toBe(run.answer);
  });

  it('恰好二十轮且已读到第一轮时不显示更早记录入口', async () => {
    vi.mocked(employeeWorkspace.conversation).mockResolvedValue({ ...conversation, last_turn: 20, active_run_id: null });
    vi.mocked(employeeWorkspace.history).mockResolvedValue(Array.from({ length: 20 }, (_, i) => ({ ...run,
      run_id: `run_${i + 1}`, turn_no: i + 1, status: 'SUCCEEDED', stage: 'FINISHED' })));
    const changed = vi.fn();
    const { result } = renderHook(() => useEmployeeConversation('app', 'conv', changed));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.hasEarlier).toBe(false);
  });
});

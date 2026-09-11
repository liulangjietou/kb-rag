// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmployeeApiError, subscribeEmployeeRun } from '../../api/employeeWorkspace';
import { useEmployeeConversation } from './useEmployeeConversation';

vi.mock('../../api/employeeWorkspace', async (original) => {
  const module = await original<typeof import('../../api/employeeWorkspace')>();
  return { ...module, employeeWorkspace: {
    conversation: vi.fn().mockResolvedValue({ conversation_id: 'conv', app_id: 'app', title: '售后咨询', last_turn: 1, active_run_id: 'run' }),
    history: vi.fn().mockResolvedValue([]),
  }, subscribeEmployeeRun: vi.fn() };
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

describe('员工重连退避', () => {
  it('长时间连接失败仍计入失败次数，不能因等待时间长而无限重试', async () => {
    vi.useFakeTimers();
    let observedTime = 0;
    vi.spyOn(Date, 'now').mockImplementation(() => observedTime);
    vi.mocked(subscribeEmployeeRun).mockImplementation(async () => {
      observedTime += 135_000;
      throw new EmployeeApiError('STREAM_TIMEOUT', '连接等待超时', true);
    });
    const changed = vi.fn();
    const { result } = renderHook(() => useEmployeeConversation('app', 'conv', changed));
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    await act(async () => { await vi.advanceTimersByTimeAsync(31_000); });
    expect(subscribeEmployeeRun).toHaveBeenCalledTimes(6);
    expect(result.current.connection).toBe('paused');
  });
});

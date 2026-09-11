// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BrowserRouter, useNavigate } from 'react-router-dom';

afterEach(() => { cleanup(); vi.restoreAllMocks(); window.history.replaceState(null, '', '/'); });

describe('命令式站内导航的安全边界', () => {
  it.each([String.raw`/\router-audit.invalid`, String.raw`/\\router-audit.invalid`])('拒绝会被浏览器解释为其他主机的路径 %s', (target) => {
    const { result } = renderHook(useNavigate, { wrapper: BrowserRouter });
    const push = vi.spyOn(window.history, 'pushState');
    expect(() => result.current(target)).toThrow('External navigation is not allowed');
    expect(push).not.toHaveBeenCalled();
  });

  it('合法员工会话地址保留应用和会话参数', () => {
    const { result } = renderHook(useNavigate, { wrapper: BrowserRouter });
    act(() => result.current('/workspace?app=app_fixture&conversation=conv_fixture'));
    expect(window.location.pathname).toBe('/workspace');
    expect(window.location.search).toBe('?app=app_fixture&conversation=conv_fixture');
  });
});

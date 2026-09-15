// @vitest-environment jsdom
import { Suspense } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { employeeWorkspace, type EmployeeConversation } from '../../api/employeeWorkspace';
import EmployeeWorkspacePage from './EmployeeWorkspacePage';

vi.mock('../../auth/AuthContext', () => {
  const logout = vi.fn();
  return { useAuth: () => ({ logout }) };
});
vi.mock('./ConversationView', () => ({ default: () => <p>已打开会话</p> }));
vi.mock('../../api/employeeWorkspace', async (original) => ({
  ...await original<typeof import('../../api/employeeWorkspace')>(),
  employeeWorkspace: { applications: vi.fn(), conversations: vi.fn(), create: vi.fn() },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

beforeEach(() => {
  window.history.replaceState({ key: 'workspace' }, '', '/workspace?app=app');
  vi.mocked(employeeWorkspace.applications).mockResolvedValue([{ app_id: 'app', name: '知识助手',
    released_version_id: 'version', released_version: '1.0' }]);
  vi.mocked(employeeWorkspace.conversations).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); window.history.replaceState(null, '', '/'); });

it('目标页尚在加载时，旧页面的新建响应也不能覆盖已发生的导航', async () => {
  const response = deferred<EmployeeConversation>();
  const homeReady = deferred<void>();
  let ready = false;
  vi.mocked(employeeWorkspace.create).mockReturnValue(response.promise);
  function Home() {
    if (!ready) throw homeReady.promise;
    return <h1>工作概览</h1>;
  }
  function Navigation() {
    const navigate = useNavigate();
    return <button onClick={() => navigate('/home')}>回到首页</button>;
  }
  render(<BrowserRouter><Navigation /><Suspense fallback={<p>加载页面</p>}><Routes>
    <Route path="/workspace" element={<EmployeeWorkspacePage />} />
    <Route path="/home" element={<Home />} />
  </Routes></Suspense></BrowserRouter>);
  const create = screen.getByRole('button', { name: /新对话$/ });
  await waitFor(() => expect((create as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(create);
  fireEvent.click(screen.getByRole('button', { name: '回到首页' }));
  expect(window.location.pathname).toBe('/home');
  // 新页面暂未提交，旧页尚未卸载；这是 CI 命中的时间窗口。
  await act(async () => { response.resolve({ conversation_id: 'late' } as EmployeeConversation); });
  expect(window.location.pathname).toBe('/home');
  await act(async () => { ready = true; homeReady.resolve(); });
  expect(screen.getByRole('heading', { name: '工作概览' })).toBeTruthy();
});

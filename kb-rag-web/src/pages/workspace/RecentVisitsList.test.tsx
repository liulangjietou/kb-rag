// @vitest-environment jsdom
import { App as AntApp } from 'antd';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { ResourceVisit } from '../../api/resourceVisit';
import { PERMISSIONS } from '../../auth/permissions';
import RecentVisitsList from './RecentVisitsList';

const mocks = vi.hoisted(() => ({ list: vi.fn(), clear: vi.fn(), auth: vi.fn() }));
vi.mock('../../api/resourceVisit', () => ({ listResourceVisits: mocks.list, clearResourceVisits: mocks.clear }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: mocks.auth }));
const kb: ResourceVisit = { resource_type: 'KB', resource_id: 'kb_1', name: '访问过的知识库', visited_at: '2026-09-10T13:20:00' };
const app: ResourceVisit = { resource_type: 'APP', resource_id: 'app_1', name: '访问过的应用', visited_at: '2026-09-09T10:00:00' };
function setAuth(permissions: string[] = [PERMISSIONS.KB_READ, PERMISSIONS.APP_READ], token = 'session-one', kbIds: string[] = []) {
  mocks.auth.mockReturnValue({ token, user: { username: 'operator', permissions, kb_ids: kbIds }, can: (permission: string) => permissions.includes(permission) });
}
function Probe() { return <output aria-label="path">{useLocation().pathname}</output>; }
function View() { return <AntApp><MemoryRouter><RecentVisitsList /><Probe /></MemoryRouter></AntApp>; }
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
beforeEach(() => { vi.resetAllMocks(); setAuth(); mocks.list.mockResolvedValue([kb, app]); mocks.clear.mockResolvedValue(undefined); });
afterEach(cleanup);

it('部分权限撤销立即清除旧记录并重新按当前权限读取', async () => {
  const view = render(<View />);
  await screen.findByText(kb.name);
  const latest = deferred<ResourceVisit[]>();
  mocks.list.mockReturnValueOnce(latest.promise);
  setAuth([PERMISSIONS.APP_READ]);
  view.rerender(<View />);
  expect(screen.queryByText(kb.name)).toBeNull();
  await act(async () => latest.resolve([app]));
  expect(await screen.findByText(app.name)).toBeTruthy();
  expect(mocks.list).toHaveBeenCalledTimes(2);
});

it('清空中的旧会话不能覆盖新会话的访问记录', async () => {
  const view = render(<View />);
  await screen.findByText(kb.name);
  const clearing = deferred<void>();
  mocks.clear.mockReturnValueOnce(clearing.promise);
  fireEvent.click(screen.getByRole('button', { name: '清空记录' }));
  fireEvent.click(await screen.findByRole('button', { name: /^清\s*空$/ }));
  await waitFor(() => expect(mocks.clear).toHaveBeenCalledOnce());
  mocks.list.mockResolvedValueOnce([{ ...kb, name: '新会话的资源' }]);
  setAuth([PERMISSIONS.KB_READ], 'session-two', ['kb_1']);
  view.rerender(<View />);
  expect(await screen.findByText('新会话的资源')).toBeTruthy();
  await act(async () => clearing.resolve());
  expect(screen.getByText('新会话的资源')).toBeTruthy();
  expect(screen.getByRole('button', { name: '刷新记录' }).hasAttribute('disabled')).toBe(false);
});

it('访问记录使用服务端顺序和访问时间，点击回到准确资源', async () => {
  render(<View />);
  const row = await screen.findByRole('button', { name: /访问过的知识库/ });
  expect(row.textContent).toContain('访问于');
  expect(row.textContent).toContain('13:20');
  expect(screen.getAllByRole('button', { name: /继续查看/ }).map((node) => node.textContent)).toEqual([row.textContent, expect.stringContaining(app.name)]);
  fireEvent.click(row);
  expect(screen.getByLabelText('path').textContent).toBe('/kb/kb_1');
});

it('读取失败有重试入口，写入结果不确定时不保留旧列表', async () => {
  mocks.list.mockRejectedValueOnce(new Error('offline'));
  render(<View />);
  await screen.findByText('访问记录加载失败，请刷新重试。');
  expect(screen.queryByText('打开知识库或应用后，会在这里显示最近访问')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '刷新记录' }));
  await screen.findByText(kb.name);
  mocks.clear.mockRejectedValueOnce(new Error('connection lost'));
  fireEvent.click(screen.getByRole('button', { name: '清空记录' }));
  fireEvent.click(await screen.findByRole('button', { name: /^清\s*空$/ }));
  await screen.findByText('未能确认清空结果，请刷新访问记录后核对。');
  expect(screen.queryByText(kb.name)).toBeNull();
  mocks.list.mockResolvedValueOnce([]);
  fireEvent.click(screen.getByRole('button', { name: '刷新记录' }));
  await screen.findByText('打开知识库或应用后，会在这里显示最近访问');
});

it('失去全部读取权限不再发请求，迟到响应不重新展示记录', async () => {
  const pending = deferred<ResourceVisit[]>();
  mocks.list.mockReturnValueOnce(pending.promise);
  const view = render(<View />);
  setAuth([]);
  view.rerender(<View />);
  await act(async () => pending.resolve([kb]));
  expect(screen.queryByText(kb.name)).toBeNull();
  await screen.findByText('当前没有可展示的知识库或应用');
  expect(mocks.list).toHaveBeenCalledOnce();
});

it('知识库范围变化而读取能力不变时，也不能接受旧范围的迟到结果', async () => {
  const pending = deferred<ResourceVisit[]>();
  mocks.list.mockReturnValueOnce(pending.promise).mockResolvedValueOnce([{ ...kb, name: '范围内的新名称' }]);
  const view = render(<View />);
  setAuth([PERMISSIONS.KB_READ, PERMISSIONS.APP_READ], 'session-one', ['kb_1']);
  view.rerender(<View />);
  await screen.findByText('范围内的新名称');
  await act(async () => pending.resolve([app]));
  expect(screen.queryByText(app.name)).toBeNull();
  expect(screen.getByText('范围内的新名称')).toBeTruthy();
  expect(mocks.list).toHaveBeenCalledTimes(2);
});

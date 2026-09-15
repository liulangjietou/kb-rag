// @vitest-environment jsdom
import { App as AntApp } from 'antd';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { KnowledgeTodo } from '../../api/knowledgeTodo';
import { PERMISSIONS } from '../../auth/permissions';
import KnowledgeTodos from './KnowledgeTodos';

const mocks = vi.hoisted(() => ({ list: vi.fn(), auth: vi.fn() }));
vi.mock('../../api/knowledgeTodo', () => ({ listKnowledgeTodos: mocks.list }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: mocks.auth }));
const todo: KnowledgeTodo = { kb_id: 'kb_one', kb_name: '服务规范', kind: 'PENDING_CONFIRM', total: 7, can_process: false };
function auth(kbIds = ['kb_one'], permissions: string[] = [PERMISSIONS.KB_READ]) {
  mocks.auth.mockReturnValue({ token: 'session', user: { kb_ids: kbIds, permissions }, can: (p: string) => permissions.includes(p) });
}
function Probe() { const location = useLocation(); return <output aria-label="path">{location.pathname}{location.search}</output>; }
function View() { return <AntApp><MemoryRouter><KnowledgeTodos /><Probe /></MemoryRouter></AntApp>; }
beforeEach(() => { vi.resetAllMocks(); auth(); mocks.list.mockResolvedValue([todo]); });
afterEach(cleanup);

it('只读待办可查看，跳转包含准确知识库与任务种类', async () => {
  render(<View />);
  const row = await screen.findByRole('button', { name: /解析待确认.*服务规范.*7.*查看/ });
  expect(screen.queryByText('去处理')).toBeNull();
  fireEvent.click(row);
  expect(screen.getByLabelText('path').textContent).toBe('/kb/kb_one?todo=PENDING_CONFIRM');
});

it('根范围改变时立即移除旧待办，迟到的旧请求不能恢复旧名称', async () => {
  let resolve!: (items: KnowledgeTodo[]) => void;
  mocks.list.mockReturnValueOnce(new Promise<KnowledgeTodo[]>((yes) => { resolve = yes; }));
  const view = render(<View />);
  auth(['kb_two']);
  mocks.list.mockResolvedValueOnce([{ ...todo, kb_id: 'kb_two', kb_name: '当前可见知识库' }]);
  view.rerender(<View />);
  await screen.findByText('当前可见知识库');
  await act(async () => resolve([todo]));
  expect(screen.queryByText(todo.kb_name)).toBeNull();
  expect(mocks.list).toHaveBeenCalledTimes(2);
});

it('加载失败与无待办分开，刷新可以恢复；撤权后不继续读取', async () => {
  mocks.list.mockRejectedValueOnce(new Error('offline'));
  const view = render(<View />);
  await screen.findByText('待办加载失败');
  expect(screen.queryByText('当前授权范围内暂无知识待办')).toBeNull();
  mocks.list.mockResolvedValueOnce([]);
  fireEvent.click(screen.getByRole('button', { name: '刷新待办' }));
  await screen.findByText('当前授权范围内暂无知识待办');
  auth([], []);
  view.rerender(<View />);
  expect(screen.queryByRole('region', { name: '知识处理待办' })).toBeNull();
  expect(mocks.list).toHaveBeenCalledTimes(2);
});

it('长列表按服务端顺序逐组展开，不丢失后续待办', async () => {
  mocks.list.mockResolvedValue(Array.from({ length: 8 }, (_, n) => ({ ...todo, kb_id: `kb_${n}`, kb_name: `知识库 ${n}` })));
  render(<View />);
  await screen.findByText('知识库 0');
  expect(screen.queryByText('知识库 7')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '查看全部 8 组待办' }));
  expect(screen.getByText('知识库 7')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '收起待办' }));
  expect(screen.queryByText('知识库 7')).toBeNull();
});

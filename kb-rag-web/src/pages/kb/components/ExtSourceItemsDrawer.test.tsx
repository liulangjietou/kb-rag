// @vitest-environment jsdom
import { StrictMode } from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { ExtSource, ExtSourceItem, PageResult } from '../../../api/types';
import ExtSourceItemsDrawer from './ExtSourceItemsDrawer';

const mocks = vi.hoisted(() => ({ list: vi.fn(), retry: vi.fn(), auth: vi.fn() }));
vi.mock('../../../api/extSource', () => ({ listExtSourceItems: mocks.list, retryFailedExtSource: mocks.retry }));
vi.mock('../../../auth/AuthContext', () => ({ useAuth: mocks.auth }));
const source = { source_id: 'source_one', source_type: 's3', name: '来源一' } as ExtSource;
const result = (key: string): PageResult<ExtSourceItem> => ({
  items: [{ object_key: key, last_status: 'FAILED', last_error: '连接超时' } as ExtSourceItem],
  total: 1, page: 1, size: 20,
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(yes => { resolve = yes; });
  return { promise, resolve };
}
function View({ selected = source }: { selected?: ExtSource }) {
  return <StrictMode><ExtSourceItemsDrawer source={selected} onClose={() => {}} /></StrictMode>;
}
beforeEach(() => {
  vi.resetAllMocks();
  mocks.auth.mockReturnValue({ token: 'session', can: () => true });
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: vi.fn().mockImplementation(query => ({
    matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
  })) });
});
afterEach(cleanup);

it('来源切换后丢弃旧对象的迟到响应', async () => {
  const pending = deferred<PageResult<ExtSourceItem>>();
  mocks.list.mockImplementation((id: string) => id === 'source_one' ? pending.promise : Promise.resolve(result('current.md')));
  const view = render(<View />);
  await waitFor(() => expect(mocks.list).toHaveBeenCalled());
  view.rerender(<View selected={{ ...source, source_id: 'source_two', name: '来源二' }} />);
  await screen.findByText('current.md', { exact: true });
  await act(async () => pending.resolve(result('old-private.md')));
  expect(screen.queryByText('old-private.md', { exact: true })).toBeNull();
  expect(screen.getByText('current.md', { exact: true })).toBeTruthy();
});

it('登录会话变化时，旧读取结果不能进入新会话明细', async () => {
  const pending = deferred<PageResult<ExtSourceItem>>();
  mocks.list.mockReturnValue(pending.promise);
  const view = render(<View />);
  await waitFor(() => expect(mocks.list).toHaveBeenCalled());
  mocks.auth.mockReturnValue({ token: 'new-session', can: () => false });
  mocks.list.mockResolvedValue(result('new-session.md'));
  view.rerender(<View />);
  await screen.findByText('new-session.md', { exact: true });
  await act(async () => pending.resolve(result('old-private.md')));
  expect(screen.queryByText('old-private.md', { exact: true })).toBeNull();
  expect(screen.queryByRole('button', { name: '仅重试失败项' })).toBeNull();
});

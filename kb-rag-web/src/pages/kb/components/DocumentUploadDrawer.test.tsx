// @vitest-environment jsdom
import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { KbDocument } from '../../../api/types';
import DocumentUploadDrawer from './DocumentUploadDrawer';

const mocks = vi.hoisted(() => ({ upload: vi.fn(), auth: vi.fn(), accepted: vi.fn() }));
vi.mock('../../../api/document', () => ({ uploadDocument: mocks.upload }));
vi.mock('../../../auth/AuthContext', () => ({ useAuth: mocks.auth }));
const result = { doc_id: 'doc_one', version: 'V1', duplicated: false } as KbDocument;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => { resolve = yes; });
  return { promise, resolve };
}
function View({ kbId = 'kb_one', open = true }: { kbId?: string; open?: boolean }) {
  return <StrictMode><DocumentUploadDrawer kbId={kbId} open={open} onClose={() => {}} onAccepted={mocks.accepted} /></StrictMode>;
}
function choose(files: File[]) {
  fireEvent.change(document.querySelector('input[type=file]')!, { target: { files } });
}
beforeEach(() => {
  vi.resetAllMocks();
  mocks.auth.mockReturnValue({ token: 'session', user: { kb_ids: ['kb_one'] } });
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: vi.fn().mockImplementation((query) => ({
    matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(),
  })) });
});
afterEach(cleanup);

it('严格模式下最多两路上传，同名文件串行且不会重复启动', async () => {
  const responses = Array.from({ length: 4 }, () => deferred<KbDocument>());
  let next = 0;
  mocks.upload.mockImplementation(() => responses[next++].promise);
  render(<View />);
  choose([new File(['a'], 'same.md'), new File(['aa'], 'same.md'), new File(['b'], 'b.md'), new File(['c'], 'c.md')]);
  await waitFor(() => expect(mocks.upload).toHaveBeenCalledTimes(2));
  expect(mocks.upload.mock.calls.map((call) => call[1].name)).toEqual(['same.md', 'b.md']);
  await act(async () => responses[0].resolve(result));
  await waitFor(() => expect(mocks.upload).toHaveBeenCalledTimes(3));
  expect(mocks.upload.mock.calls[2][1].size).toBe(2);
  await act(async () => responses[1].resolve(result));
  await waitFor(() => expect(mocks.upload).toHaveBeenCalledTimes(4));
  await act(async () => { responses[2].resolve(result); responses[3].resolve(result); });
  await waitFor(() => expect(mocks.accepted).toHaveBeenCalledTimes(4));
  expect(screen.getAllByText('已接收', { exact: true })).toHaveLength(4);
});

it('保留失败文件与重开记录，只重试失败项并显示服务端去重结果', async () => {
  mocks.upload.mockImplementation((_kb, file: File) => file.name === 'good.md' ? Promise.resolve(result) : Promise.reject(new Error('lost')));
  const view = render(<View />);
  choose([new File(['good'], 'good.md'), new File(['bad'], 'bad.md')]);
  await screen.findByRole('button', { name: '重试 bad.md' });
  view.rerender(<View open={false} />);
  view.rerender(<View />);
  expect(screen.getByRole('button', { name: '重试 bad.md' })).toBeTruthy();
  expect(mocks.upload).toHaveBeenCalledTimes(2);
  mocks.upload.mockResolvedValue({ ...result, duplicated: true });
  fireEvent.click(screen.getByRole('button', { name: '仅重试失败文件' }));
  await screen.findByText('bad.md 内容与已有版本 V1一致，未重复建版');
  expect(mocks.upload.mock.calls.map((call) => call[1].name)).toEqual(['good.md', 'bad.md', 'bad.md']);
});

it('切换知识库后不继续旧队列，迟到结果不刷新或污染新页面', async () => {
  const pending = deferred<KbDocument>();
  mocks.upload.mockReturnValue(pending.promise);
  const view = render(<View />);
  choose([new File(['a'], 'a.md'), new File(['b'], 'b.md'), new File(['c'], 'c.md')]);
  await waitFor(() => expect(mocks.upload).toHaveBeenCalledTimes(2));
  view.rerender(<View kbId="kb_two" />);
  await act(async () => pending.resolve(result));
  expect(mocks.upload).toHaveBeenCalledTimes(2);
  expect(mocks.accepted).not.toHaveBeenCalled();
  expect(screen.queryByText('a.md', { exact: true })).toBeNull();
  expect(screen.queryByText('c.md', { exact: true })).toBeNull();
});

it.each([
  { token: 'another-session', user: { kb_ids: ['kb_one'] } },
  { token: 'session', user: { kb_ids: ['kb_one', 'kb_two'] } },
])('当前知识库不变时，身份或授权范围更新也会隔离旧批次 %j', async (auth) => {
  const pending = deferred<KbDocument>();
  mocks.upload.mockReturnValue(pending.promise);
  const view = render(<View />);
  choose([new File(['a'], 'private-a.md'), new File(['b'], 'private-b.md'), new File(['c'], 'private-c.md')]);
  await waitFor(() => expect(mocks.upload).toHaveBeenCalledTimes(2));
  mocks.auth.mockReturnValue(auth);
  view.rerender(<View />);
  await act(async () => pending.resolve(result));
  expect(mocks.upload).toHaveBeenCalledTimes(2);
  expect(mocks.accepted).not.toHaveBeenCalled();
  expect(screen.queryByText('private-a.md', { exact: true })).toBeNull();
  expect(screen.queryByText('private-c.md', { exact: true })).toBeNull();
});

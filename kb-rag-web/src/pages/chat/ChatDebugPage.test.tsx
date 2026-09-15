// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { ChatStreamHandlers } from '../../api/chatStream';
import ChatDebugPage from './ChatDebugPage';

const mocks = vi.hoisted(() => ({ token: 'session_a', apps: vi.fn(), stream: vi.fn() }));
vi.mock('../../api/app', () => ({ listPreviewApps: mocks.apps, streamChatPreview: mocks.stream, chatPreview: vi.fn() }));
vi.mock('../../api/kb', () => ({ listKnowledgeBases: vi.fn() }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: mocks.token, permissions: ['search:debug'],
  kbIds: [], kbScopeAll: true, can: () => false }) }));
vi.mock('../../components/ImagePicker', () => ({ default: () => null, toImagesPayload: () => undefined }));
vi.mock('../../components/PageHeader', () => ({ default: () => <h1>问答调试</h1> }));

beforeEach(() => {
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn() })));
  HTMLElement.prototype.scrollIntoView = vi.fn();
  mocks.apps.mockResolvedValue([{ app_id: 'app_a', name: '应用', versions: [
    { app_version_id: 'av_a', version: 'v1', status: 'DRAFT' },
  ] }]);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.resetAllMocks(); mocks.token = 'session_a'; });

it('身份切换同步清除上一账号的回答，并忽略原流迟到内容', async () => {
  let callbacks!: ChatStreamHandlers;
  mocks.stream.mockImplementation((_app, _payload, handlers: ChatStreamHandlers) => {
    callbacks = handlers;
    return new Promise<void>(() => undefined);
  });
  const view = render(<ChatDebugPage />);
  await act(async () => undefined);
  fireEvent.change(screen.getByPlaceholderText('输入问题，回车发送（Shift+回车换行）'), { target: { value: '问题' } });
  fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
  act(() => callbacks.onDelta('旧账号回答'));
  expect(screen.getByText('旧账号回答')).toBeTruthy();

  mocks.token = 'session_b';
  mocks.apps.mockReturnValue(new Promise(() => undefined));
  view.rerender(<ChatDebugPage />);
  expect(screen.queryByText('旧账号回答')).toBeNull();
  act(() => callbacks.onDelta('迟到内容'));
  expect(screen.queryByText(/迟到内容/)).toBeNull();
});

it('每轮按首个非空增量和接收终态记录浏览器时间，与服务端时间分开', async () => {
  let now = 1000;
  vi.spyOn(performance, 'now').mockImplementation(() => now);
  let callbacks!: ChatStreamHandlers;
  let finish!: () => void;
  mocks.stream.mockImplementation((_app, _payload, handlers: ChatStreamHandlers) => {
    callbacks = handlers;
    return new Promise<void>(resolve => { finish = resolve; });
  });
  render(<ChatDebugPage />);
  await act(async () => undefined);
  fireEvent.change(screen.getByPlaceholderText('输入问题，回车发送（Shift+回车换行）'), { target: { value: '计时问题' } });
  fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
  now = 1100; act(() => callbacks.onDelta(''));
  now = 1300; act(() => callbacks.onDelta('首段'));
  now = 1800; act(() => callbacks.onDelta('后续'));
  now = 2100;
  await act(async () => {
    callbacks.onDiagnostics?.({ outcome: 'SUCCEEDED', retrieval_ms: 200, generation_ms: 700, first_delta_ms: 250, total_ms: 1000 });
    callbacks.onDone('req_measured', [], []);
    finish();
  });
  expect(screen.getByText('浏览器首段等待').nextElementSibling?.textContent).toBe('300 ms');
  expect(screen.getByText('浏览器接收总耗时').nextElementSibling?.textContent).toBe('1,100 ms');
  expect(screen.getByText('服务端首段等待').nextElementSibling?.textContent).toBe('250 ms');
});

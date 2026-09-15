// @vitest-environment jsdom
import { StrictMode } from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import EmployeeFeedbackDrawer from './EmployeeFeedbackDrawer';

const mocks = vi.hoisted(() => ({ get: vi.fn(), auth: vi.fn() }));
vi.mock('../../../api/employeeFeedback', () => ({ getEmployeeFeedback: mocks.get }));
vi.mock('../../../auth/AuthContext', () => ({ useAuth: mocks.auth }));
vi.mock('../../../api/qualityIssue', () => ({ qualityFailure: () => ({ restricted: true }) }));
vi.mock('antd', () => ({
  Drawer: ({ children }: { children: React.ReactNode }) => <div role="dialog">{children}</div>,
  Button: ({ children, onClick }: { children: React.ReactNode; onClick: () => void }) => <button onClick={onClick}>{children}</button>,
  Spin: () => <span>正在确认</span>, Alert: ({ message }: { message: string }) => <p>{message}</p>,
  Typography: { Title: ({ children }: { children: React.ReactNode }) => <h4>{children}</h4> },
}));
const detail = (question: string) => ({ run_id: 'run', question, answer: '待核对', feedback_verdict: 'BAD', citations: [], app_version: 'v1' });
afterEach(() => { cleanup(); vi.resetAllMocks(); });

it('StrictMode 的旧读取晚到时不能替换最新授权结果', async () => {
  mocks.auth.mockReturnValue({ token: 'session', can: () => true });
  let resolveOld!: (value: unknown) => void;
  const old = new Promise(resolve => { resolveOld = resolve; });
  mocks.get.mockReturnValueOnce(old).mockResolvedValueOnce(detail('当前问题'));
  render(<StrictMode><EmployeeFeedbackDrawer kbId="kb" runId="run" onClose={() => undefined} /></StrictMode>);
  await act(async () => undefined);
  expect(screen.getByText('当前问题')).toBeTruthy();
  await act(async () => { resolveOld(detail('旧问题')); await old; });
  expect(screen.queryByText('旧问题')).toBeNull();
  expect(screen.getByText('当前问题')).toBeTruthy();
});

it('切换身份立即移除旧内容，旧身份迟到响应不能恢复原回答', async () => {
  mocks.auth.mockReturnValue({ token: 'old-session', can: () => true });
  let resolveOld!: (value: unknown) => void;
  const old = new Promise(resolve => { resolveOld = resolve; });
  mocks.get.mockReturnValueOnce(old).mockRejectedValueOnce(new Error('forbidden'));
  const { rerender } = render(<EmployeeFeedbackDrawer kbId="kb" runId="run" onClose={() => undefined} />);
  mocks.auth.mockReturnValue({ token: 'new-session', can: () => true });
  rerender(<EmployeeFeedbackDrawer kbId="kb" runId="run" onClose={() => undefined} />);
  await act(async () => { resolveOld(detail('上一身份的私有内容')); await old; });
  expect(screen.queryByText('上一身份的私有内容')).toBeNull();
  expect(screen.getByText('该反馈或依赖资料已不可访问，原回答已隐藏。')).toBeTruthy();
});

// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { TenantSummary } from '../../../api/types';
import ModelUsageDrawer from './ModelUsageDrawer';

const mocks = vi.hoisted(() => ({ summary: vi.fn(), records: vi.fn(), auth: vi.fn() }));
vi.mock('../../../api/modelUsage', () => ({ getModelUsageSummary: mocks.summary, listModelUsageRecords: mocks.records }));
vi.mock('../../../auth/AuthContext', () => ({ useAuth: mocks.auth }));
vi.mock('antd', () => {
  const Box = ({ children }: { children: React.ReactNode }) => <div>{children}</div>;
  return { Alert: ({ message }: { message: string }) => <p>{message}</p>, Card: Box, Space: Box,
    Descriptions: Object.assign(Box, { Item: Box }), Progress: () => null,
    Drawer: ({ open, title, children }: { open: boolean; title: string; children: React.ReactNode }) => open ? <div role="dialog" aria-label={title}>{children}</div> : null,
    Input: ({ value, onChange }: { value: string; onChange: React.ChangeEventHandler<HTMLInputElement> }) => <input aria-label="用量月份" value={value} onChange={onChange} />,
    Button: ({ children, onClick }: { children: React.ReactNode; onClick: () => void }) => <button onClick={onClick}>{children}</button>,
    Table: ({ dataSource }: { dataSource: { usage_id: string; model: string }[] }) => <div>{dataSource.map(row => <p key={row.usage_id}>{row.model}</p>)}</div>,
    Tag: Box, Typography: { Text: Box, Title: Box, Paragraph: Box }, Empty: Box,
    Modal: ({ open, children }: { open: boolean; children: React.ReactNode }) => open ? <div role="dialog">{children}</div> : null,
  };
});

it('月份切换后旧请求晚到仍不能恢复上一月份记录', async () => {
  mocks.auth.mockReturnValue({ token: 'session', can: () => true });
  let finishOld!: (data: unknown) => void;
  const old = new Promise(resolve => { finishOld = resolve; });
  mocks.summary.mockResolvedValue(summary('租户'));
  mocks.records.mockReturnValueOnce(old).mockResolvedValueOnce(rows('新月份记录'));
  render(<ModelUsageDrawer tenant={tenant('租户')} onClose={() => undefined} />);
  const input = screen.getByLabelText('用量月份') as HTMLInputElement;
  const next = input.value === '2025-01' ? '2025-02' : '2025-01';
  fireEvent.change(input, { target: { value: next } });
  await act(async () => undefined);
  expect(screen.getByText('新月份记录')).toBeTruthy();
  expect(mocks.records).toHaveBeenLastCalledWith('租户', next, 1, 20);
  await act(async () => { finishOld(rows('旧月份记录')); await old; });
  expect(screen.queryByText('旧月份记录')).toBeNull();
});

it('同月刷新完成后晚到的初次请求不能回填旧数据', async () => {
  mocks.auth.mockReturnValue({ token: 'session', can: () => true });
  let finishOld!: (data: unknown) => void;
  const old = new Promise(resolve => { finishOld = resolve; });
  mocks.summary.mockResolvedValue(summary('租户'));
  mocks.records.mockReturnValueOnce(old).mockResolvedValueOnce(rows('刷新后的记录'));
  render(<ModelUsageDrawer tenant={tenant('租户')} onClose={() => undefined} />);
  fireEvent(window, new Event('focus'));
  await act(async () => undefined);
  expect(screen.getByText('刷新后的记录')).toBeTruthy();
  await act(async () => { finishOld(rows('初次读取的旧记录')); await old; });
  expect(screen.queryByText('初次读取的旧记录')).toBeNull();
});

it('登录身份变更会清除旧记录，即使当前租户没有变化', async () => {
  mocks.auth.mockReturnValue({ token: 'old-session', can: () => true });
  mocks.summary.mockResolvedValue(summary('租户'));
  mocks.records.mockResolvedValueOnce(rows('旧身份记录')).mockReturnValueOnce(new Promise(() => undefined));
  const { rerender } = render(<ModelUsageDrawer tenant={tenant('租户')} onClose={() => undefined} />);
  await act(async () => undefined);
  expect(screen.getByText('旧身份记录')).toBeTruthy();
  mocks.auth.mockReturnValue({ token: 'new-session', can: () => true });
  rerender(<ModelUsageDrawer tenant={tenant('租户')} onClose={() => undefined} />);
  expect(screen.queryByText('旧身份记录')).toBeNull();
});

it('管理权限被撤回后隐藏用量视图并拒绝晚到的数据', async () => {
  mocks.auth.mockReturnValue({ token: 'session', can: () => true });
  let finish!: (data: unknown) => void;
  const pending = new Promise(resolve => { finish = resolve; });
  mocks.summary.mockResolvedValue(summary('租户'));
  mocks.records.mockReturnValueOnce(pending);
  const { rerender } = render(<ModelUsageDrawer tenant={tenant('租户')} onClose={() => undefined} />);
  mocks.auth.mockReturnValue({ token: 'session', can: () => false });
  rerender(<ModelUsageDrawer tenant={tenant('租户')} onClose={() => undefined} />);
  await act(async () => { finish(rows('无权查看的记录')); await pending; });
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.queryByText('无权查看的记录')).toBeNull();
});
const tenant = (name: string): TenantSummary => ({ tenant_id: name, name, code: name, status: 'ENABLED', builtin: false, monthly_token_quota: 1000, created_at: '' });
const summary = (id: string) => ({ tenant_id: id, month: '2026-09', quota_tokens: 1000, used_tokens: 10, reserved_tokens: 0, remaining_tokens: 990, estimated_calls: 0, unpriced_calls: 0, costs: [] });
const rows = (model: string) => ({ items: [{ usage_id: model, model }], page: 1, size: 20, total: 1 });
afterEach(() => { cleanup(); vi.resetAllMocks(); });

it('切换租户后旧请求晚到不能把旧用量显示在新租户名下', async () => {
  mocks.auth.mockReturnValue({ token: 'session', can: () => true });
  let finishOld!: (data: unknown) => void;
  const old = new Promise(resolve => { finishOld = resolve; });
  mocks.summary.mockImplementation((id: string) => Promise.resolve(summary(id)));
  mocks.records.mockReturnValueOnce(old).mockResolvedValueOnce(rows('新租户模型'));
  const { rerender } = render(<ModelUsageDrawer tenant={tenant('原租户')} onClose={() => undefined} />);
  rerender(<ModelUsageDrawer tenant={tenant('新租户')} onClose={() => undefined} />);
  await act(async () => undefined);
  expect(screen.getByRole('dialog', { name: '模型用量 - 新租户' })).toBeTruthy();
  expect(screen.getByText('新租户模型')).toBeTruthy();
  await act(async () => { finishOld(rows('原租户的旧记录')); await old; });
  expect(screen.queryByText('原租户的旧记录')).toBeNull();
  expect(screen.getByText('新租户模型')).toBeTruthy();
});

it('新租户仍在加载时立即清除已显示的旧租户用量', async () => {
  mocks.auth.mockReturnValue({ token: 'session', can: () => true });
  mocks.summary.mockImplementation((id: string) => Promise.resolve(summary(id)));
  mocks.records.mockResolvedValueOnce(rows('原租户记录')).mockReturnValueOnce(new Promise(() => undefined));
  const { rerender } = render(<ModelUsageDrawer tenant={tenant('原租户')} onClose={() => undefined} />);
  await act(async () => undefined);
  expect(screen.getByText('原租户记录')).toBeTruthy();
  rerender(<ModelUsageDrawer tenant={tenant('新租户')} onClose={() => undefined} />);
  expect(screen.queryByText('原租户记录')).toBeNull();
});

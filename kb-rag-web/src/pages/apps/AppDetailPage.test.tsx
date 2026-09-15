// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { KbApp } from '../../api/types';
import AppDetailPage from './AppDetailPage';

const mocks = vi.hoisted(() => ({ appId: 'app-a', get: vi.fn(), versions: vi.fn(), created: Promise.resolve() }));
vi.mock('react-router-dom', () => ({ useParams: () => ({ appId: mocks.appId }), useNavigate: () => vi.fn() }));
vi.mock('../../api/app', () => ({ getApp: mocks.get, listAppVersions: mocks.versions }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'session', can: (code: string) => code === 'app:read' }) }));
vi.mock('../../hooks/useResourceVisit', () => ({ useResourceVisit: vi.fn() }));
vi.mock('../../components/PageHeader', () => ({ default: ({ title }: { title: string }) => <h1>{title}</h1> }));
vi.mock('./components/AppConfigTab', () => ({ default: ({ appId, onVersionCreated }: { appId: string; onVersionCreated: () => Promise<void> }) =>
  <div>配置 {appId}<button onClick={() => { mocks.created = onVersionCreated().catch(() => undefined); }}>模拟草稿创建成功</button></div> }));
vi.mock('./components/AppVersionTab', () => ({ default: () => null }));
vi.mock('./components/ApiDebugTab', () => ({ default: () => null }));
vi.mock('antd', () => {
  const Box = ({ children }: { children: React.ReactNode }) => <div>{children}</div>;
  return { Alert: ({ message }: { message: string }) => <div>{message}</div>, Button: Box, Spin: Box, Typography: { Text: Box },
    Tabs: ({ items }: { items: { key: string; children: React.ReactNode }[] }) => <>{items.map(item => <div key={item.key}>{item.children}</div>)}</> };
});
const app = (id: string, name: string) => ({ app_id: id, name }) as KbApp;
afterEach(() => { cleanup(); vi.resetAllMocks(); mocks.appId = 'app-a'; });

it('切换应用期间清空旧详情并禁止原响应重新填入编辑器', async () => {
  let finishOld!: (value: KbApp) => void;
  const old = new Promise<KbApp>(resolve => { finishOld = resolve; });
  mocks.get.mockReturnValueOnce(old).mockResolvedValueOnce(app('app-b', '当前应用'));
  mocks.versions.mockResolvedValue([]);
  const { rerender } = render(<AppDetailPage />);
  mocks.appId = 'app-b';
  rerender(<AppDetailPage />);
  await act(async () => undefined);
  expect(screen.getByText('当前应用')).toBeTruthy();
  await act(async () => { finishOld(app('app-a', '原应用')); await old; });
  expect(screen.queryByText('原应用')).toBeNull();
  expect(screen.getByText('当前应用')).toBeTruthy();
});

it('新应用仍在加载时不显示旧应用名称或可编辑配置', async () => {
  mocks.get.mockResolvedValueOnce(app('app-a', '原应用')).mockReturnValueOnce(new Promise(() => undefined));
  mocks.versions.mockResolvedValue([]);
  const { rerender } = render(<AppDetailPage />);
  await act(async () => undefined);
  expect(screen.getByText('原应用')).toBeTruthy();
  mocks.appId = 'app-b';
  rerender(<AppDetailPage />);
  expect(screen.queryByText('原应用')).toBeNull();
  expect(screen.queryByText('配置 app-b')).toBeNull();
});

it('创建成功后的版本刷新失败时退出旧配置并提供恢复提示', async () => {
  mocks.get.mockResolvedValueOnce(app('app-a', '当前应用'));
  mocks.versions.mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('unavailable'));
  render(<AppDetailPage />);
  await act(async () => undefined);
  fireEvent.click(screen.getByText('模拟草稿创建成功'));
  await act(async () => { await mocks.created; });
  expect(screen.getByText('应用详情加载失败')).toBeTruthy();
  expect(screen.queryByText('模拟草稿创建成功')).toBeNull();
});

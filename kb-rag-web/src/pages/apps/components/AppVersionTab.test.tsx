// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { AppVersion } from '../../../api/types';
import AppVersionTab from './AppVersionTab';

const mocks = vi.hoisted(() => ({ list: vi.fn() }));
vi.mock('../../../api/app', () => ({ listAppVersions: mocks.list, bindGateDataset: vi.fn(),
  releaseAppVersion: vi.fn(), rollbackAppVersion: vi.fn(), submitAppVersionForTest: vi.fn() }));
vi.mock('../../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'session', can: (code: string) => code === 'app:read' }) }));
vi.mock('./GateCompareDrawer', () => ({ default: () => null }));
vi.mock('antd', () => {
  const Box = ({ children }: { children: React.ReactNode }) => <div>{children}</div>;
  return { Alert: Box, Button: Box, Modal: () => null, Popconfirm: Box, Select: () => null, Space: Box, Tag: Box,
    Table: ({ dataSource }: { dataSource: AppVersion[] }) => <div>{dataSource.map(row => <p key={row.app_version_id}>{row.version}</p>)}</div>,
    Typography: { Text: Box, Paragraph: Box }, message: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn() } };
});
const version = (app: string, label: string): AppVersion => ({
  app_version_id: `${app}-version`, app_id: app, version: label, status: 'DRAFT',
  config: { kb_refs: [], retrieval: { recall_top_k: 50, top_n: 5 }, prompt: { system_prompt: '',
    refusal_enabled: true, refusal_prompt: '', leak_guard_enabled: true, leak_guard_prompt: '', citation_enabled: true } },
  gate_dataset_id: null, gate_run_ids: [], gate_verdict: null, force_released: false, changelog: null, created_at: '', updated_at: '',
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

it('切换应用后晚到的原应用版本不能覆盖当前版本或回传父页面', async () => {
  let finishOld!: (versions: AppVersion[]) => void;
  const old = new Promise<AppVersion[]>(resolve => { finishOld = resolve; });
  const current = [version('app-b', '当前应用版本')];
  mocks.list.mockReturnValueOnce(old).mockResolvedValueOnce(current);
  const changed = vi.fn();
  const { rerender } = render(<AppVersionTab appId="app-a" kbs={[]} onVersionsChanged={changed} />);
  rerender(<AppVersionTab appId="app-b" kbs={[]} onVersionsChanged={changed} />);
  await act(async () => undefined);
  expect(screen.getByText('当前应用版本')).toBeTruthy();
  await act(async () => { finishOld([version('app-a', '原应用版本')]); await old; });
  expect(screen.queryByText('原应用版本')).toBeNull();
  expect(changed).toHaveBeenLastCalledWith(current);
});

it('新应用读取尚未完成时立即隐藏上一应用版本', async () => {
  mocks.list.mockResolvedValueOnce([version('app-a', '原应用版本')]).mockReturnValueOnce(new Promise(() => undefined));
  const changed = vi.fn();
  const { rerender } = render(<AppVersionTab appId="app-a" kbs={[]} onVersionsChanged={changed} />);
  await act(async () => undefined);
  expect(screen.getByText('原应用版本')).toBeTruthy();
  rerender(<AppVersionTab appId="app-b" kbs={[]} onVersionsChanged={changed} />);
  expect(screen.queryByText('原应用版本')).toBeNull();
});

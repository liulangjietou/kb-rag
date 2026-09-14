// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { AppVersion } from '../../../api/types';
import AppVersionDiffDrawer from './AppVersionDiffDrawer';

const mocks = vi.hoisted(() => ({ list: vi.fn(), token: 'session-a' }));
vi.mock('../../../api/app', () => ({ listAppVersions: mocks.list }));
vi.mock('../../../auth/AuthContext', () => ({ useAuth: () => ({ token: mocks.token, can: () => true }) }));
vi.mock('./AppVersionCorpusDiff', () => ({ default: () => null }));
vi.mock('antd', () => {
  const Box = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  return { Alert: Box, Button: Box, Drawer: Box, Empty: Box, Select: () => null, Spin: Box, Tag: Box, Typography: { Paragraph: Box } };
});
const version = (id: string, model: string): AppVersion => ({ app_version_id: id, app_id: 'app', version: id, status: 'DRAFT',
  config: { kb_refs: [], chat_model: model, retrieval: { recall_top_k: 50, top_n: 5 },
    prompt: { system_prompt: '', refusal_enabled: true, refusal_prompt: '', leak_guard_enabled: true, leak_guard_prompt: '', citation_enabled: true } },
  gate_dataset_id: null, gate_run_ids: [], gate_verdict: null, force_released: false, changelog: null,
  created_at: '2026-09-14T12:00:00', updated_at: '2026-09-14T12:00:00' });
afterEach(() => { cleanup(); vi.resetAllMocks(); mocks.token = 'session-a'; });

it('切换身份时立即销毁上一身份的配置快照', async () => {
  mocks.list.mockResolvedValueOnce([version('v1', '原身份模型')]).mockReturnValueOnce(new Promise(() => undefined));
  const close = vi.fn();
  const { rerender } = render(<AppVersionDiffDrawer appId="app" versionId="v1" kbs={[]} onClose={close} />);
  await act(async () => undefined);
  expect(screen.getByText('原身份模型')).toBeTruthy();
  mocks.token = 'session-b';
  rerender(<AppVersionDiffDrawer appId="app" versionId="v1" kbs={[]} onClose={close} />);
  expect(screen.queryByText('原身份模型')).toBeNull();
});

it('已关闭版本的迟到响应不能覆盖新版本差异', async () => {
  let finishOld!: (rows: AppVersion[]) => void;
  const old = new Promise<AppVersion[]>(resolve => { finishOld = resolve; });
  mocks.list.mockReturnValueOnce(old).mockResolvedValueOnce([version('v2', '当前模型')]);
  const close = vi.fn();
  const { rerender } = render(<AppVersionDiffDrawer appId="app" versionId="v1" kbs={[]} onClose={close} />);
  rerender(<AppVersionDiffDrawer appId="app" versionId="v2" kbs={[]} onClose={close} />);
  await act(async () => undefined);
  expect(screen.getByText('当前模型')).toBeTruthy();
  await act(async () => { finishOld([version('v1', '原版本模型')]); await old; });
  expect(screen.queryByText('原版本模型')).toBeNull();
  expect(screen.getByText('当前模型')).toBeTruthy();
});

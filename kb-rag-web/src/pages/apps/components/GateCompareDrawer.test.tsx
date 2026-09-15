// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { AppVersion } from '../../../api/types';
import GateCompareDrawer from './GateCompareDrawer';
const mocks = vi.hoisted(() => ({ compare: vi.fn() }));
vi.mock('../../../api/evalRun', () => ({ compareEvalRuns: mocks.compare }));
vi.mock('antd', () => {
  const Box = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  return { Alert: ({ message }: { message: string }) => <div>{message}</div>, Button: Box,
    Descriptions: Object.assign(Box, { Item: Box }), Drawer: Box, Empty: ({ description }: { description: string }) => <div>{description}</div>,
    Radio: { Group: Box, Button: Box }, Space: Box, Spin: Box, Table: () => null, Tag: Box,
    Typography: { Title: Box, Text: Box }, message: { error: vi.fn() } };
});
const version = (id: string) => ({ app_version_id: id, version: id, status: 'GATE_PASSED', gate_run_ids: [id + '-run'] }) as AppVersion;
afterEach(() => { cleanup(); vi.resetAllMocks(); });
it('另一个门禁结果加载失败时不能保留上一版本的结论', async () => {
  mocks.compare.mockResolvedValueOnce({ comparable: false, reason: '原版本不可比原因', runs: [] })
    .mockRejectedValueOnce(new Error('unavailable'));
  const { rerender } = render(<GateCompareDrawer version={version('v1')} onClose={vi.fn()} />);
  await act(async () => undefined);
  expect(screen.getByText('双跑结果不可比')).toBeTruthy();
  rerender(<GateCompareDrawer version={version('v2')} onClose={vi.fn()} />);
  await act(async () => undefined);
  expect(screen.queryByText('双跑结果不可比')).toBeNull();
  expect(screen.getByText('门禁双跑结果加载失败')).toBeTruthy();
});

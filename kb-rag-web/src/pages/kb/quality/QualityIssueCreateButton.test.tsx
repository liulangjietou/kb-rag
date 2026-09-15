// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { QualityIssue } from '../../../api/qualityIssue';
import QualityIssueCreateButton from './QualityIssueCreateButton';

const { create } = vi.hoisted(() => ({ create: vi.fn() }));
vi.mock('../../../api/qualityIssue', () => ({ createQualityIssue: create }));
vi.mock('antd', () => ({ Button: ({ loading, onClick, children }: {
  loading: boolean; onClick: () => void; children: React.ReactNode;
}) => <button disabled={loading} onClick={onClick}>{children}</button> }));
afterEach(() => { cleanup(); vi.clearAllMocks(); });

it('来源切换后忽略旧建单响应，并允许新来源独立提交', async () => {
  let finishOld!: (issue: QualityIssue) => void;
  const oldRequest = new Promise<QualityIssue>((resolve) => { finishOld = resolve; });
  create.mockReturnValueOnce(oldRequest).mockResolvedValueOnce({ issue_id: 'new-issue' });
  const onOpen = vi.fn();
  const { rerender } = render(<QualityIssueCreateButton kbId="old-kb" sourceType="ZERO_HIT" sourceId="old-source" onOpen={onOpen} />);
  fireEvent.click(screen.getByRole('button'));
  rerender(<QualityIssueCreateButton kbId="new-kb" sourceType="ZERO_HIT" sourceId="new-source" onOpen={onOpen} />);
  await act(async () => { finishOld({ issue_id: 'old-issue' } as QualityIssue); await oldRequest; });
  expect(onOpen).not.toHaveBeenCalled();
  await act(async () => { fireEvent.click(screen.getByRole('button')); });
  expect(create).toHaveBeenLastCalledWith('new-kb', 'ZERO_HIT', 'new-source');
  expect(onOpen).toHaveBeenCalledExactlyOnceWith('new-issue');
});

it('卸载后到达的响应不会再打开详情', async () => {
  let finish!: (issue: QualityIssue) => void;
  const request = new Promise<QualityIssue>((resolve) => { finish = resolve; });
  create.mockReturnValueOnce(request);
  const onOpen = vi.fn();
  const { unmount } = render(<QualityIssueCreateButton kbId="kb" sourceType="ZERO_HIT" sourceId="source" onOpen={onOpen} />);
  fireEvent.click(screen.getByRole('button'));
  unmount();
  await act(async () => { finish({ issue_id: 'late-issue' } as QualityIssue); await request; });
  expect(onOpen).not.toHaveBeenCalled();
});

// @vitest-environment jsdom
import { StrictMode } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useResourceVisit } from './useResourceVisit';
const record = vi.hoisted(() => vi.fn());
vi.mock('../api/resourceVisit', () => ({ recordResourceVisit: record }));
function Visit({ ready, id = 'kb_a' }: { ready: boolean; id?: string }) {
  useResourceVisit('KB', id, ready);
  const navigate = useNavigate();
  return <button onClick={() => navigate('/kb/kb_a')}>再次打开</button>;
}
function View({ ready, id }: { ready: boolean; id?: string }) {
  return <StrictMode><MemoryRouter><Visit ready={ready} id={id} /></MemoryRouter></StrictMode>;
}
beforeEach(() => { record.mockReset().mockResolvedValue(undefined); });
afterEach(cleanup);
it('详情成功前不记录，StrictMode与轮询重新渲染只记一次，新导航再次记录', async () => {
  const view = render(<View ready={false} />);
  expect(record).not.toHaveBeenCalled();
  view.rerender(<View ready />);
  await waitFor(() => expect(record).toHaveBeenCalledExactlyOnceWith('KB', 'kb_a'));
  view.rerender(<View ready={false} />);
  view.rerender(<View ready />);
  expect(record).toHaveBeenCalledOnce();
  fireEvent.click(screen.getByRole('button', { name: '再次打开' }));
  await waitFor(() => expect(record).toHaveBeenCalledTimes(2));
});
it('资源切换仅在新详情成功后记录，失败不阻塞页面或自动重试', async () => {
  record.mockRejectedValue(new Error('offline'));
  const view = render(<View ready />);
  await waitFor(() => expect(record).toHaveBeenCalledOnce());
  view.rerender(<View ready={false} id="kb_b" />);
  expect(record).toHaveBeenCalledOnce();
  view.rerender(<View ready id="kb_b" />);
  await waitFor(() => expect(record).toHaveBeenCalledTimes(2));
  expect(record).toHaveBeenLastCalledWith('KB', 'kb_b');
  expect(screen.getByRole('button')).toBeTruthy();
});

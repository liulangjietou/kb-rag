// @vitest-environment jsdom
// Author: owlzhangfq@gmail.com
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ModelStatusProvider, useModelStatus } from './ModelStatusContext';

const mocks = vi.hoisted(() => ({
  getModelStatus: vi.fn(),
  useAuth: vi.fn(),
}));

vi.mock('../api/system', () => ({ getModelStatus: mocks.getModelStatus }));
vi.mock('../auth/AuthContext', () => ({ useAuth: mocks.useAuth }));

function Probe() {
  const { modelStatus, loading, error, refresh } = useModelStatus();
  return (
    <div>
      <span>{loading ? 'loading' : error ? 'error' : modelStatus ? 'ready' : 'unavailable'}</span>
      <button type="button" onClick={refresh}>refresh</button>
    </div>
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ModelStatusProvider permission boundary', () => {
  it('缺少 system:config 和 kb:read 时不请求受限接口', async () => {
    mocks.useAuth.mockReturnValue({ canAny: () => false });
    render(<ModelStatusProvider><Probe /></ModelStatusProvider>);

    expect(await screen.findByText('unavailable')).toBeTruthy();
    expect(mocks.getModelStatus).not.toHaveBeenCalled();
  });

  it('持有允许权限时读取模型状态', async () => {
    mocks.useAuth.mockReturnValue({ canAny: () => true });
    mocks.getModelStatus.mockResolvedValue({
      embedding_configured: true,
      provider: 'provider',
      model: 'embedding-model',
      dimension: 1024,
      rerank_configured: false,
      rerank_provider: null,
      rerank_model: null,
      chat_configured: false,
      chat_provider: null,
      chat_model: null,
      vector_engine: 'elasticsearch',
      vision_configured: false,
      vision_provider: null,
      vision_model: null,
    });
    render(<ModelStatusProvider><Probe /></ModelStatusProvider>);

    await waitFor(() => expect(screen.getByText('ready')).toBeTruthy());
    expect(mocks.getModelStatus).toHaveBeenCalledOnce();
  });

  it('权限撤销后忽略撤销前仍在途的模型状态响应', async () => {
    let allowed = true;
    let resolveStatus: ((value: unknown) => void) | undefined;
    mocks.useAuth.mockImplementation(() => ({ canAny: () => allowed }));
    mocks.getModelStatus.mockReturnValue(new Promise((resolve) => {
      resolveStatus = resolve;
    }));
    const view = render(<ModelStatusProvider><Probe /></ModelStatusProvider>);
    expect(await screen.findByText('loading')).toBeTruthy();

    allowed = false;
    view.rerender(<ModelStatusProvider><Probe /></ModelStatusProvider>);
    expect(await screen.findByText('unavailable')).toBeTruthy();
    resolveStatus?.({ embedding_configured: true });

    await waitFor(() => expect(screen.getByText('unavailable')).toBeTruthy());
    expect(screen.queryByText('ready')).toBeNull();
  });

  it('最新刷新失败时清除旧成功状态并暴露失败态', async () => {
    mocks.useAuth.mockReturnValue({ canAny: () => true });
    mocks.getModelStatus
      .mockResolvedValueOnce({ embedding_configured: true })
      .mockRejectedValueOnce(new Error('unavailable'));
    render(<ModelStatusProvider><Probe /></ModelStatusProvider>);
    expect(await screen.findByText('ready')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'refresh' }));

    expect(await screen.findByText('error')).toBeTruthy();
    expect(screen.queryByText('ready')).toBeNull();
  });
});

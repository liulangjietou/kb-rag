// @vitest-environment jsdom
// Author: owlzhangfq@gmail.com
import { App as AntApp } from 'antd';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { useLocation } from 'react-router-dom';
import { PERMISSIONS } from '../auth/permissions';
import HomePage from './HomePage';

const mocks = vi.hoisted(() => ({
  listKnowledgeBases: vi.fn(),
  listApps: vi.fn(),
  listRegistrationReviews: vi.fn(),
  useAuth: vi.fn(),
  useModelStatus: vi.fn(),
}));

vi.mock('../api/kb', () => ({ listKnowledgeBases: mocks.listKnowledgeBases }));
vi.mock('../api/app', () => ({ listApps: mocks.listApps }));
vi.mock('../api/registration', () => ({ listRegistrationReviews: mocks.listRegistrationReviews }));
vi.mock('../auth/AuthContext', () => ({ useAuth: mocks.useAuth }));
vi.mock('../context/ModelStatusContext', () => ({ useModelStatus: mocks.useModelStatus }));

function authWith(granted: string[]) {
  const permissionSet = new Set(granted);
  return {
    displayName: 'Richard',
    can: (permission: string) => permissionSet.has(permission),
  };
}

function LocationProbe() {
  return <output aria-label="current path">{useLocation().pathname}</output>;
}

beforeEach(() => {
  mocks.listKnowledgeBases.mockResolvedValue([{
    kb_id: 'kb-1',
    name: '真实知识库',
    description: '来自接口的描述',
    index_config: null,
    current_config_fingerprint: null,
    created_at: '2026-08-31T12:00:00Z',
  }]);
  mocks.listApps.mockResolvedValue([]);
  mocks.listRegistrationReviews.mockResolvedValue({ items: [], page: 1, size: 1, total: 0 });
  mocks.useModelStatus.mockReturnValue({ modelStatus: null, loading: false, refresh: vi.fn() });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('HomePage permission-aware data loading', () => {
  it('证据链只陈述接口能够证明的配置与发布事实', async () => {
    mocks.useAuth.mockReturnValue(authWith([PERMISSIONS.APP_READ]));
    mocks.listApps.mockResolvedValue([{
      app_id: 'released-app',
      name: '已发布应用',
      description: null,
      released_version: 'V1.0',
      released_version_id: 'version-1',
      created_at: '2026-08-31T12:00:00Z',
      updated_at: '2026-08-31T12:00:00Z',
    }, {
      app_id: 'draft-app',
      name: '草稿应用',
      description: null,
      created_at: '2026-08-31T12:00:00Z',
      updated_at: '2026-08-31T12:00:00Z',
    }]);
    mocks.useModelStatus.mockReturnValue({
      modelStatus: { embedding_configured: true },
      loading: false,
      refresh: vi.fn(),
    });

    render(<AntApp><MemoryRouter><HomePage /></MemoryRouter></AntApp>);

    await waitFor(() => expect(mocks.listApps).toHaveBeenCalledOnce());
    expect(screen.getByText('向量模型')).toBeTruthy();
    expect(screen.getByText('CONFIGURED')).toBeTruthy();
    expect(screen.getByText('混合检索')).toBeTruthy();
    expect(screen.getByText('HYBRID AVAILABLE')).toBeTruthy();
    expect(screen.getByText('1 RELEASED')).toBeTruthy();
    expect(screen.queryByText('向量索引')).toBeNull();
    expect(screen.queryByText('引用回答')).toBeNull();
  });

  it('只请求有权限的真实资源，并裁剪无权快捷入口', async () => {
    mocks.useAuth.mockReturnValue(authWith([PERMISSIONS.KB_READ]));
    render(<AntApp><MemoryRouter><HomePage /><LocationProbe /></MemoryRouter></AntApp>);

    expect(await screen.findByText('真实知识库')).toBeTruthy();
    expect(screen.getByText('来自接口的描述')).toBeTruthy();
    expect(mocks.listKnowledgeBases).toHaveBeenCalledOnce();
    expect(mocks.listApps).not.toHaveBeenCalled();
    expect(mocks.listRegistrationReviews).not.toHaveBeenCalled();
    expect(screen.getByText('进入知识库')).toBeTruthy();
    expect(screen.queryByText('进入应用中心')).toBeNull();
    expect(screen.queryByText('注册审核')).toBeNull();

    const search = screen.getByRole('searchbox', { name: '搜索已授权的知识库或应用' }) as HTMLInputElement;
    fireEvent.change(search, { target: { value: '真实' } });
    const results = await screen.findByRole('list', { name: '匹配的知识资源' });
    expect(within(results).getByRole('button', { name: /真实知识库/ })).toBeTruthy();
    fireEvent.keyDown(search, { key: 'Enter' });
    expect(screen.getByLabelText('current path').textContent).toBe('/kb/kb-1');
    fireEvent.keyDown(search, { key: 'Escape' });
    expect(search.value).toBe('');
  });

  it('无功能权限时不发资源请求并显示真实空态', async () => {
    mocks.useAuth.mockReturnValue(authWith([]));
    render(<AntApp><MemoryRouter><HomePage /></MemoryRouter></AntApp>);

    expect(await screen.findByText('当前没有可展示的知识库或应用')).toBeTruthy();
    expect(screen.getByText('暂无可用功能，请联系管理员分配角色')).toBeTruthy();
    await waitFor(() => {
      expect(mocks.listKnowledgeBases).not.toHaveBeenCalled();
      expect(mocks.listApps).not.toHaveBeenCalled();
      expect(mocks.listRegistrationReviews).not.toHaveBeenCalled();
    });
  });

  it('局部失败不伪装成空数据，重试后恢复真实资源', async () => {
    mocks.useAuth.mockReturnValue(authWith([PERMISSIONS.KB_READ]));
    mocks.listKnowledgeBases
      .mockRejectedValueOnce(new Error('temporary unavailable'))
      .mockResolvedValueOnce([{
        kb_id: 'kb-recovered',
        name: '重试恢复知识库',
        description: '恢复后的真实数据',
        index_config: null,
        current_config_fingerprint: null,
        created_at: '2026-08-31T12:00:00Z',
      }]);
    render(<AntApp><MemoryRouter><HomePage /></MemoryRouter></AntApp>);

    expect(await screen.findByText('资源数据加载失败')).toBeTruthy();
    expect(screen.queryByText('当前没有可展示的知识库或应用')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /重试/ }));

    expect(await screen.findByText('重试恢复知识库')).toBeTruthy();
    await waitFor(() => expect(mocks.listKnowledgeBases).toHaveBeenCalledTimes(2));
    expect(screen.queryByText('资源数据加载失败')).toBeNull();
  });

  it('重试失败时清除上一轮资源，不把陈旧数据继续伪装成实时结果', async () => {
    mocks.useAuth.mockReturnValue(authWith([
      PERMISSIONS.KB_READ,
      PERMISSIONS.USER_MANAGE,
      PERMISSIONS.TENANT_MANAGE,
    ]));
    mocks.listKnowledgeBases
      .mockResolvedValueOnce([{
        kb_id: 'kb-stale',
        name: '上一轮知识库',
        description: '该数据不应在失败后保留',
        index_config: null,
        current_config_fingerprint: null,
        created_at: '2026-08-31T12:00:00Z',
      }])
      .mockRejectedValueOnce(new Error('temporary unavailable'));
    mocks.listRegistrationReviews
      .mockRejectedValueOnce(new Error('review service unavailable'))
      .mockResolvedValueOnce({ items: [], page: 1, size: 1, total: 0 });
    render(<AntApp><MemoryRouter><HomePage /></MemoryRouter></AntApp>);

    expect(await screen.findByText('上一轮知识库')).toBeTruthy();
    expect(await screen.findByText('部分首页数据暂不可用')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /重试/ }));

    expect(await screen.findByText('资源数据加载失败')).toBeTruthy();
    expect(screen.queryByText('上一轮知识库')).toBeNull();
    expect(screen.queryByText('该数据不应在失败后保留')).toBeNull();
  });

  it('会话内权限撤销时立即清除旧资源且不留下可点击入口', async () => {
    let granted = [PERMISSIONS.KB_READ];
    mocks.useAuth.mockImplementation(() => authWith(granted));
    const view = render(<AntApp><MemoryRouter><HomePage /></MemoryRouter></AntApp>);
    expect(await screen.findByText('真实知识库')).toBeTruthy();

    granted = [];
    view.rerender(<AntApp><MemoryRouter><HomePage /></MemoryRouter></AntApp>);

    expect(screen.queryByText('真实知识库')).toBeNull();
    expect(screen.queryByText('进入知识库')).toBeNull();
    expect(await screen.findByText('当前没有可展示的知识库或应用')).toBeTruthy();
    expect(mocks.listKnowledgeBases).toHaveBeenCalledOnce();
  });

  it('首页统一重试会同时刷新模型状态，不留下无效按钮', async () => {
    const refreshModelStatus = vi.fn();
    mocks.useAuth.mockReturnValue(authWith([PERMISSIONS.KB_READ]));
    mocks.useModelStatus.mockReturnValue({
      modelStatus: null,
      loading: false,
      error: true,
      refresh: refreshModelStatus,
    });
    render(<AntApp><MemoryRouter><HomePage /></MemoryRouter></AntApp>);

    expect(await screen.findByText('部分首页数据暂不可用')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /重试/ }));

    expect(refreshModelStatus).toHaveBeenCalledOnce();
  });
});

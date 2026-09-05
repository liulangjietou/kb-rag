// @vitest-environment jsdom
// Author: owlzhangfq@gmail.com
import { App as AntApp } from 'antd';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import RegistrationReviewPage from './RegistrationReviewPage';

const mocks = vi.hoisted(() => ({
  listRegistrationReviews: vi.fn(),
  approveRegistration: vi.fn(),
  rejectRegistration: vi.fn(),
  listTenants: vi.fn(),
  listRoles: vi.fn(),
}));

vi.mock('../../api/registration', () => ({
  listRegistrationReviews: mocks.listRegistrationReviews,
  approveRegistration: mocks.approveRegistration,
  rejectRegistration: mocks.rejectRegistration,
}));
vi.mock('../../api/tenant', () => ({ listTenants: mocks.listTenants }));
vi.mock('../../api/role', () => ({ listRoles: mocks.listRoles }));

const pendingApplication = {
  application_id: 'REG-1',
  email: 'lin@example.com',
  display_name: '林澈',
  team_name: '知识平台组',
  application_note: '维护企业知识库',
  status: 'PENDING' as const,
  email_verified_at: '2026-08-31T12:00:00Z',
  created_at: '2026-08-31T12:00:00Z',
};

beforeEach(() => {
  mocks.listRegistrationReviews.mockResolvedValue({ items: [pendingApplication], page: 1, size: 10, total: 1 });
  mocks.listTenants.mockResolvedValue([
    { tenant_id: 'tenant-a', code: 'A', name: '租户甲', status: 'ENABLED', builtin: false, monthly_token_quota: 0, created_at: '' },
    { tenant_id: 'tenant-b', code: 'B', name: '租户乙', status: 'ENABLED', builtin: false, monthly_token_quota: 0, created_at: '' },
  ]);
  mocks.listRoles.mockResolvedValue([
    {
      role_id: 'role-a', tenant_id: 'tenant-a', code: 'EDITOR_A', name: '编辑者 A', description: null,
      builtin: false, kb_scope_all: false, kb_ids: [], permission_codes: ['kb:read', 'kb:write'],
    },
    {
      role_id: 'role-b', tenant_id: 'tenant-b', code: 'VIEWER_B', name: '访客 B', description: null,
      builtin: false, kb_scope_all: false, kb_ids: [], permission_codes: ['kb:read'],
    },
  ]);
  mocks.approveRegistration.mockResolvedValue({
    ...pendingApplication,
    status: 'APPROVED',
    tenant_id: 'tenant-b',
    role_ids: ['role-b'],
    reviewed_at: '2026-08-31T13:00:00Z',
    rejection_reason: null,
  });
  mocks.rejectRegistration.mockResolvedValue({
    ...pendingApplication,
    status: 'REJECTED',
    tenant_id: null,
    role_ids: [],
    reviewed_at: '2026-08-31T13:00:00Z',
    rejection_reason: '当前申请信息与企业身份不匹配',
  });
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }),
  });
  class ResizeObserverMock {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, value: ResizeObserverMock });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('RegistrationReviewPage', () => {
  it('切换租户会清空角色，且必须重新选择该租户至少一个角色才能通过', async () => {
    render(<AntApp><MemoryRouter><RegistrationReviewPage /></MemoryRouter></AntApp>);
    expect(await screen.findByText('林澈')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '审核' }));

    const tenantSelect = await screen.findByRole('combobox', { name: '所属租户' });
    fireEvent.mouseDown(tenantSelect);
    fireEvent.click(await screen.findByText('租户甲（A）'));

    let roleSelect = screen.getByRole('combobox', { name: '分配角色' });
    fireEvent.mouseDown(roleSelect);
    fireEvent.click(await screen.findByText('编辑者 A（EDITOR_A）'));

    fireEvent.mouseDown(tenantSelect);
    fireEvent.click(await screen.findByText('租户乙（B）'));
    fireEvent.click(screen.getByRole('button', { name: /通过并开通账号/ }));
    await waitFor(() => expect(mocks.approveRegistration).not.toHaveBeenCalled());

    roleSelect = screen.getByRole('combobox', { name: '分配角色' });
    fireEvent.mouseDown(roleSelect);
    fireEvent.click(await screen.findByText('访客 B（VIEWER_B）'));
    mocks.approveRegistration.mockRejectedValueOnce(new Error('temporary failure'));
    fireEvent.click(screen.getByRole('button', { name: /通过并开通账号/ }));

    expect(await screen.findByText(/账号开通失败/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /通过并开通账号/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /通过并开通账号/ }));
    await waitFor(() => expect(mocks.approveRegistration).toHaveBeenCalledTimes(2));
    expect(mocks.approveRegistration).toHaveBeenLastCalledWith('REG-1', {
      tenant_id: 'tenant-b',
      role_ids: ['role-b'],
    });
  }, 20_000);

  it('驳回失败时保留弹窗和已填写原因', async () => {
    mocks.rejectRegistration.mockRejectedValueOnce(new Error('temporary failure'));
    render(<AntApp><MemoryRouter><RegistrationReviewPage /></MemoryRouter></AntApp>);
    expect(await screen.findByText('林澈')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '审核' }));
    fireEvent.click(await screen.findByRole('button', { name: '驳回申请' }));

    const reason = await screen.findByPlaceholderText('例如：请使用企业域邮箱重新提交申请');
    fireEvent.change(reason, { target: { value: '当前申请信息与企业身份不匹配' } });
    fireEvent.click(screen.getByRole('button', { name: '确认驳回' }));

    expect(await screen.findByText(/驳回提交失败/)).toBeTruthy();
    expect((reason as HTMLTextAreaElement).value).toBe('当前申请信息与企业身份不匹配');
    expect(screen.getAllByRole('dialog').length).toBeGreaterThanOrEqual(2);
  }, 15_000);

  it('变更成功但后台刷新失败时仍立即移除旧待审核行', async () => {
    mocks.listRegistrationReviews
      .mockResolvedValueOnce({ items: [pendingApplication], page: 1, size: 10, total: 1 })
      .mockRejectedValueOnce(new Error('refresh failed'));
    render(<AntApp><MemoryRouter><RegistrationReviewPage /></MemoryRouter></AntApp>);
    expect(await screen.findByText('林澈')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '审核' }));
    fireEvent.click(await screen.findByRole('button', { name: '驳回申请' }));

    fireEvent.change(await screen.findByPlaceholderText('例如：请使用企业域邮箱重新提交申请'), {
      target: { value: '当前申请信息与企业身份不匹配' },
    });
    fireEvent.click(screen.getByRole('button', { name: '确认驳回' }));

    await waitFor(() => expect(screen.queryByText('林澈')).toBeNull());
    expect(await screen.findByText('注册申请列表加载失败')).toBeTruthy();
    expect(mocks.rejectRegistration).toHaveBeenCalledOnce();
  }, 15_000);

  it('终态详情展示审核当时的租户与角色快照', async () => {
    mocks.listRegistrationReviews.mockResolvedValueOnce({
      items: [{
        ...pendingApplication,
        status: 'APPROVED',
        tenant_id: 'tenant-b',
        role_ids: ['role-b'],
        reviewed_at: '2026-08-31T13:00:00Z',
      }],
      page: 1,
      size: 10,
      total: 1,
    });
    render(<AntApp><MemoryRouter><RegistrationReviewPage /></MemoryRouter></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '查看' }));

    expect(await screen.findByText('租户乙（B）')).toBeTruthy();
    expect(screen.getByText('访客 B（VIEWER_B）')).toBeTruthy();
  });
});

// @vitest-environment jsdom
// Author: owlzhangfq@gmail.com
import { App as AntApp } from 'antd';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { isStrongPassword } from '../utils/registrationPassword';
import RegisterPage from './RegisterPage';

const mocks = vi.hoisted(() => ({
  sendRegistrationCode: vi.fn(),
  verifyRegistrationEmail: vi.fn(),
  createRegistration: vi.fn(),
}));

vi.mock('../api/registration', () => mocks);

vi.mock('../components/LoginSliderCaptcha', () => ({
  default: ({ onVerified }: { onVerified: (proof: string) => void }) => (
    <button type="button" onClick={() => onVerified('captcha-proof-1')}>完成注册滑块</button>
  ),
}));

vi.mock('../components/ThemePresetSwitcher', () => ({ default: () => null }));

function renderPage() {
  return render(
    <AntApp>
      <MemoryRouter initialEntries={['/register']}>
        <RegisterPage />
      </MemoryRouter>
    </AntApp>,
  );
}

beforeEach(() => {
  window.sessionStorage.clear();
  mocks.sendRegistrationCode.mockResolvedValue({ resend_after_seconds: 60 });
  mocks.verifyRegistrationEmail.mockResolvedValue({ registration_ticket: 'ticket-1', expires_in_seconds: 600 });
  mocks.createRegistration.mockResolvedValue({
    application_id: 'REG-1',
    email: 'richard@example.com',
    status: 'PENDING',
    created_at: '2026-08-31T12:00:00Z',
  });
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('RegisterPage', () => {
  it('密码复杂度使用 Unicode 字符分类，中文不是符号而真实标点是符号', () => {
    expect(isStrongPassword('AtlasSecure中2026')).toBe(false);
    expect(isStrongPassword('AtlasSecure！2026')).toBe(true);
    expect(isStrongPassword('AtlasSecure!²²')).toBe(false);
    expect(isStrongPassword('AtlasSecure1²')).toBe(true);
    expect(isStrongPassword('Atlas\u00a0Secure!2026')).toBe(false);
    expect(isStrongPassword('Atlas Secure!2026')).toBe(false);
    expect(isStrongPassword(`Aa1!${'x'.repeat(125)}`)).toBe(false);
    expect(isStrongPassword(`Aa1!${'x'.repeat(68)}`)).toBe(true);
    expect(isStrongPassword(`Aa1!${'x'.repeat(69)}`)).toBe(false);
    expect(isStrongPassword(`Aa1!${'中'.repeat(22)}`)).toBe(true);
    expect(isStrongPassword(`Aa1!${'中'.repeat(23)}`)).toBe(false);
  });

  it('用 captcha proof 发验证码、ticket 提交资料，并只持久化非敏感回执', async () => {
    renderPage();
    fireEvent.change(screen.getByPlaceholderText('name@company.com'), { target: { value: ' Richard@Example.com ' } });
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }));
    fireEvent.click(await screen.findByRole('button', { name: '完成注册滑块' }));

    await waitFor(() => expect(mocks.sendRegistrationCode).toHaveBeenCalledWith({
      email: 'richard@example.com',
      captcha_proof: 'captcha-proof-1',
    }));

    fireEvent.change(screen.getByPlaceholderText('输入 6 位验证码'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: '验证邮箱并继续' }));
    await waitFor(() => expect(mocks.verifyRegistrationEmail).toHaveBeenCalledWith({
      email: 'richard@example.com',
      code: '123456',
    }));

    fireEvent.change(await screen.findByPlaceholderText('你的姓名'), { target: { value: 'Richard' } });
    fireEvent.change(screen.getByPlaceholderText('例如：AI 平台组'), { target: { value: '知识平台组' } });
    const passwords = screen.getAllByPlaceholderText(/安全密码|再次输入密码/);
    fireEvent.change(passwords[0], { target: { value: 'AtlasStrong@2026' } });
    fireEvent.change(passwords[1], { target: { value: 'AtlasStrong@2026' } });
    fireEvent.change(screen.getByPlaceholderText('例如：负责产品知识库维护与内容发布'), {
      target: { value: '负责企业知识库维护与内容发布' },
    });
    fireEvent.click(screen.getByRole('button', { name: '提交注册申请' }));

    await waitFor(() => expect(mocks.createRegistration).toHaveBeenCalledWith({
      registration_ticket: 'ticket-1',
      client_submission_id: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
      ),
      display_name: 'Richard',
      team_name: '知识平台组',
      password: 'AtlasStrong@2026',
      application_note: '负责企业知识库维护与内容发布',
    }));
    await screen.findByText('注册申请已提交');

    const receipt = JSON.parse(window.sessionStorage.getItem('kb-rag-registration-receipt') ?? '{}');
    expect(receipt).toEqual({
      application_id: 'REG-1',
      email: 'richard@example.com',
      status: 'PENDING',
      created_at: '2026-08-31T12:00:00Z',
    });
    expect(JSON.stringify(receipt)).not.toContain('ticket-1');
    expect(JSON.stringify(receipt)).not.toContain('123456');
    expect(JSON.stringify(receipt)).not.toContain('AtlasStrong@2026');
  }, 15_000);

  it('刷新后恢复待审核回执，注册其他邮箱会清除它', async () => {
    window.sessionStorage.setItem('kb-rag-registration-receipt', JSON.stringify({
      application_id: 'REG-F5',
      email: 'f5@example.com',
      status: 'PENDING',
      created_at: '2026-08-31T12:00:00Z',
    }));
    renderPage();

    expect(await screen.findByText('REG-F5')).toBeTruthy();
    expect(screen.getByText(/f5@example.com 已通过邮箱验证/)).toBeTruthy();
    expect(screen.getByText(/当前审核结果以邮件通知为准/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '注册其他邮箱' }));

    expect(window.sessionStorage.getItem('kb-rag-registration-receipt')).toBeNull();
    expect(await screen.findByText('创建平台账号')).toBeTruthy();
  });

  it('网络失败后重试复用同一 client_submission_id 并恢复原申请回执', async () => {
    mocks.createRegistration.mockRejectedValueOnce(new Error('response lost'));
    renderPage();
    fireEvent.change(screen.getByPlaceholderText('name@company.com'), {
      target: { value: 'richard@example.com' },
    });
    fireEvent.change(screen.getByPlaceholderText('输入 6 位验证码'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: '验证邮箱并继续' }));

    fireEvent.change(await screen.findByPlaceholderText('你的姓名'), { target: { value: 'Richard' } });
    fireEvent.change(screen.getByPlaceholderText('例如：AI 平台组'), { target: { value: '知识平台组' } });
    const passwords = screen.getAllByPlaceholderText(/安全密码|再次输入密码/);
    fireEvent.change(passwords[0], { target: { value: 'AtlasStrong@2026' } });
    fireEvent.change(passwords[1], { target: { value: 'AtlasStrong@2026' } });
    fireEvent.change(screen.getByPlaceholderText('例如：负责产品知识库维护与内容发布'), {
      target: { value: '负责企业知识库维护与内容发布' },
    });

    fireEvent.click(screen.getByRole('button', { name: '提交注册申请' }));
    await screen.findByText('注册申请未提交，请检查信息后重试。');
    const firstSubmissionId = mocks.createRegistration.mock.calls[0][0].client_submission_id;

    fireEvent.click(screen.getByRole('button', { name: '提交注册申请' }));
    await screen.findByText('注册申请已提交');
    const secondSubmissionId = mocks.createRegistration.mock.calls[1][0].client_submission_id;

    expect(secondSubmissionId).toBe(firstSubmissionId);
    expect(window.sessionStorage.getItem('kb-rag-registration-submission-id')).toBeNull();
  });

  it('展示服务端票据剩余时间，到期后清除 ticket 并返回邮箱验证', async () => {
    mocks.verifyRegistrationEmail.mockResolvedValueOnce({
      registration_ticket: 'short-lived-ticket',
      expires_in_seconds: 1,
    });
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('name@company.com'), {
      target: { value: 'richard@example.com' },
    });
    fireEvent.change(screen.getByPlaceholderText('输入 6 位验证码'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: '验证邮箱并继续' }));

    expect(await screen.findByText(/请在 00:01 内提交/)).toBeTruthy();
    await waitFor(() => {
      expect(screen.getByText(/邮箱验证票据已过期/)).toBeTruthy();
    }, { timeout: 2500 });
    expect(screen.getByPlaceholderText('name@company.com')).toBeTruthy();
    expect(screen.queryByPlaceholderText('你的姓名')).toBeNull();
    expect(mocks.createRegistration).not.toHaveBeenCalled();
  });

  it('票据倒计时归零时等待在途提交的服务端结果，不丢成功回执', async () => {
    let resolveSubmission: ((value: unknown) => void) | undefined;
    mocks.verifyRegistrationEmail.mockResolvedValueOnce({
      registration_ticket: 'short-lived-ticket',
      expires_in_seconds: 1,
    });
    mocks.createRegistration.mockImplementationOnce(() => new Promise((resolve) => {
      resolveSubmission = resolve;
    }));
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('name@company.com'), {
      target: { value: 'richard@example.com' },
    });
    fireEvent.change(screen.getByPlaceholderText('输入 6 位验证码'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: '验证邮箱并继续' }));
    await screen.findByText(/请在 00:01 内提交/);

    fireEvent.change(screen.getByPlaceholderText('你的姓名'), { target: { value: 'Richard' } });
    fireEvent.change(screen.getByPlaceholderText('例如：AI 平台组'), { target: { value: '知识平台组' } });
    const passwords = screen.getAllByPlaceholderText(/安全密码|再次输入密码/);
    fireEvent.change(passwords[0], { target: { value: 'AtlasStrong@2026' } });
    fireEvent.change(passwords[1], { target: { value: 'AtlasStrong@2026' } });
    fireEvent.change(screen.getByPlaceholderText('例如：负责产品知识库维护与内容发布'), {
      target: { value: '负责企业知识库维护与内容发布' },
    });
    fireEvent.click(screen.getByRole('button', { name: '提交注册申请' }));

    expect(await screen.findByText(/请在 00:00 内提交/)).toBeTruthy();
    expect(screen.getByPlaceholderText('你的姓名')).toBeTruthy();
    resolveSubmission?.({
      application_id: 'REG-LATE-SUCCESS',
      email: 'richard@example.com',
      status: 'PENDING',
      created_at: '2026-08-31T12:00:00Z',
    });

    expect(await screen.findByText('REG-LATE-SUCCESS')).toBeTruthy();
    expect(screen.queryByText(/邮箱验证票据已过期/)).toBeNull();
  });

  it('提交响应丢失且票据到期后仍复用提交标识找回服务端回执', async () => {
    let rejectSubmission: ((reason?: unknown) => void) | undefined;
    mocks.verifyRegistrationEmail.mockResolvedValueOnce({
      registration_ticket: 'short-lived-ticket',
      expires_in_seconds: 1,
    });
    mocks.createRegistration.mockImplementationOnce(() => new Promise((_, reject) => {
      rejectSubmission = reject;
    }));
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('name@company.com'), {
      target: { value: 'richard@example.com' },
    });
    fireEvent.change(screen.getByPlaceholderText('输入 6 位验证码'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: '验证邮箱并继续' }));
    await screen.findByText(/请在 00:01 内提交/);

    fireEvent.change(screen.getByPlaceholderText('你的姓名'), { target: { value: 'Richard' } });
    fireEvent.change(screen.getByPlaceholderText('例如：AI 平台组'), { target: { value: '知识平台组' } });
    const passwords = screen.getAllByPlaceholderText(/安全密码|再次输入密码/);
    fireEvent.change(passwords[0], { target: { value: 'AtlasStrong@2026' } });
    fireEvent.change(passwords[1], { target: { value: 'AtlasStrong@2026' } });
    fireEvent.change(screen.getByPlaceholderText('例如：负责产品知识库维护与内容发布'), {
      target: { value: '负责企业知识库维护与内容发布' },
    });
    fireEvent.click(screen.getByRole('button', { name: '提交注册申请' }));

    expect(await screen.findByText(/请在 00:00 内提交/)).toBeTruthy();
    const firstSubmissionId = mocks.createRegistration.mock.calls[0][0].client_submission_id;
    rejectSubmission?.(new Error('response lost after server commit'));

    expect(await screen.findByText(/提交结果待确认/)).toBeTruthy();
    expect(screen.getByPlaceholderText('你的姓名')).toBeTruthy();
    expect(window.sessionStorage.getItem('kb-rag-registration-submission-id')).toBe(firstSubmissionId);

    fireEvent.click(screen.getByRole('button', { name: '提交注册申请' }));
    expect(await screen.findByText('注册申请已提交')).toBeTruthy();
    const secondSubmissionId = mocks.createRegistration.mock.calls[1][0].client_submission_id;

    expect(secondSubmissionId).toBe(firstSubmissionId);
    expect(window.sessionStorage.getItem('kb-rag-registration-submission-id')).toBeNull();
  });
});

// Author: owlzhangfq@gmail.com
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  approveRegistration,
  createRegistration,
  listRegistrationReviews,
  rejectRegistration,
  sendRegistrationCode,
  verifyRegistrationEmail,
} from './registration';

const mocks = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}));

vi.mock('./request', () => mocks);

beforeEach(() => {
  mocks.apiGet.mockReset();
  mocks.apiPost.mockReset();
});

describe('registration API contract', () => {
  it('按匿名注册契约发送验证码、验证邮箱并提交 ticket', async () => {
    const sendPayload = { email: 'richard@example.com', captcha_proof: 'captcha-proof' };
    const verifyPayload = { email: 'richard@example.com', code: '123456' };
    const registrationPayload = {
      registration_ticket: 'registration-ticket',
      client_submission_id: '123e4567-e89b-42d3-a456-426614174000',
      display_name: 'Richard',
      team_name: '知识平台组',
      password: 'AtlasStrong！2026',
      application_note: '负责知识库维护',
    };

    await sendRegistrationCode(sendPayload);
    await verifyRegistrationEmail(verifyPayload);
    await createRegistration(registrationPayload);

    expect(mocks.apiPost).toHaveBeenNthCalledWith(1, '/registrations/verification-code', sendPayload);
    expect(mocks.apiPost).toHaveBeenNthCalledWith(2, '/registrations/verify-email', verifyPayload);
    expect(mocks.apiPost).toHaveBeenNthCalledWith(3, '/registrations', registrationPayload);
  });

  it('按筛选和分页参数读取独立审核列表', async () => {
    const params = { keyword: 'Richard', status: 'PENDING' as const, page: 2, size: 20 };

    await listRegistrationReviews(params);

    expect(mocks.apiGet).toHaveBeenCalledWith('/registration-reviews', params);
  });

  it('批准和驳回接口使用申请编号与精确请求体', async () => {
    const approval = { tenant_id: 'tenant-1', role_ids: ['role-1'] };
    const rejection = { reason: '申请信息不足' };

    await approveRegistration('REG-1', approval);
    await rejectRegistration('REG-2', rejection);

    expect(mocks.apiPost).toHaveBeenNthCalledWith(1, '/registration-reviews/REG-1/approve', approval);
    expect(mocks.apiPost).toHaveBeenNthCalledWith(2, '/registration-reviews/REG-2/reject', rejection);
  });
});

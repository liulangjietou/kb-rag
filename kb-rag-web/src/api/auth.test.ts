import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createCaptchaChallenge, login, verifyCaptcha } from './auth';

const apiPost = vi.hoisted(() => vi.fn());

vi.mock('./request', () => ({
  apiGet: vi.fn(),
  apiPost,
}));

beforeEach(() => {
  apiPost.mockReset();
});

describe('login captcha api', () => {
  it('请求 challenge 与 verify 的确定端点和 snake_case DTO', () => {
    void createCaptchaChallenge();
    expect(apiPost).toHaveBeenNthCalledWith(1, '/auth/captcha/challenge');

    const payload = {
      challenge_id: 'challenge-1',
      track: [
        { x: 0, y: 0, elapsed_ms: 0 },
        { x: 500, y: 8, elapsed_ms: 180 },
        { x: 1000, y: -3, elapsed_ms: 360 },
      ],
    };
    void verifyCaptcha(payload);
    expect(apiPost).toHaveBeenNthCalledWith(2, '/auth/captcha/verify', payload);
  });

  it('login 请求必须携带一次性 captcha_proof', () => {
    const payload = {
      username: 'richard',
      password: 'browser-owned',
      mode: 'LOCAL' as const,
      captcha_proof: 'proof-1',
    };

    void login(payload);

    expect(apiPost).toHaveBeenCalledWith('/auth/login', payload);
  });
});

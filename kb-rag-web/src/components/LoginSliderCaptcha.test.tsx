// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LoginSliderCaptcha from './LoginSliderCaptcha';

const CHALLENGE = {
  challenge_id: 'challenge-1',
  track_scale: 1000,
  expires_in_seconds: 120,
  background_image: 'data:image/png;base64,AAAA',
  piece_image: 'data:image/png;base64,BBBB',
  image_width: 320,
  image_height: 160,
  piece_width: 40,
  piece_height: 40,
  piece_y: 40,
};

const NEXT_CHALLENGE = {
  ...CHALLENGE,
  challenge_id: 'challenge-2',
  background_image: 'data:image/png;base64,CCCC',
  piece_image: 'data:image/png;base64,DDDD',
  piece_y: 72,
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('LoginSliderCaptcha', () => {
  it('键盘轨迹满足后端边界并且验证请求保持 single-flight', async () => {
    let now = 1000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const requestChallenge = vi.fn().mockResolvedValue(CHALLENGE);
    const requestVerification = vi.fn().mockResolvedValue({
      captcha_proof: 'proof-1',
      expires_in_seconds: 60,
    });
    const onVerified = vi.fn();
    render(
      <LoginSliderCaptcha
        resetKey={0}
        onVerified={onVerified}
        requestChallenge={requestChallenge}
        requestVerification={requestVerification}
      />,
    );
    const slider = await screen.findByRole('slider');

    for (let index = 0; index < 20; index += 1) {
      now += 20;
      fireEvent.keyDown(slider, { key: 'ArrowRight' });
    }
    now += 20;
    fireEvent.keyDown(slider, { key: 'Enter' });
    fireEvent.keyDown(slider, { key: 'Enter' });

    await waitFor(() => expect(requestVerification).toHaveBeenCalledTimes(1));
    const payload = requestVerification.mock.calls[0]?.[0];
    expect(payload.challenge_id).toBe('challenge-1');
    expect(payload.track.length).toBeGreaterThanOrEqual(3);
    expect(payload.track.length).toBeLessThanOrEqual(200);
    expect(payload.track[0].x).toBeLessThanOrEqual(30);
    expect(payload.track.at(-1).x).toBe(400);
    expect(payload.track.at(-1).elapsed_ms).toBeGreaterThanOrEqual(300);
    expect(payload.track.every((point: { y: number }) => point.y >= -100 && point.y <= 100)).toBe(true);
    expect(onVerified).toHaveBeenCalledOnce();
    expect(onVerified).toHaveBeenCalledWith('proof-1');
    expect(screen.getByAltText('带有随机缺口的验证码背景').getAttribute('src')).toBe(CHALLENGE.background_image);
    const piece = screen.getByTestId('login-captcha-piece');
    expect(piece.style.left).toBe('35%');
    expect(piece.style.top).toBe('25%');
    expect(piece.style.width).toBe('12.5%');
    expect(piece.style.height).toBe('25%');
  });

  it('在非终点释放也提交归一化的鼠标或触摸 Pointer Events 轨迹', async () => {
    let now = 2000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const requestVerification = vi.fn().mockResolvedValue({
      captcha_proof: 'pointer-proof',
      expires_in_seconds: 60,
    });
    const onVerified = vi.fn();
    render(
      <LoginSliderCaptcha
        resetKey={0}
        onVerified={onVerified}
        requestChallenge={vi.fn().mockResolvedValue(CHALLENGE)}
        requestVerification={requestVerification}
      />,
    );
    const slider = await screen.findByRole('slider');
    const track = screen.getByTestId('login-captcha-track');
    vi.spyOn(track, 'getBoundingClientRect').mockReturnValue({
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: 300,
      bottom: 46,
      width: 300,
      height: 46,
      toJSON: () => ({}),
    });
    Object.defineProperty(slider, 'setPointerCapture', { value: vi.fn(), configurable: true });

    fireEvent.pointerDown(slider, { pointerId: 7, pointerType: 'touch', clientX: 24, clientY: 20 });
    now += 160;
    fireEvent.pointerMove(slider, { pointerId: 7, pointerType: 'touch', clientX: 100, clientY: 28 });
    now += 160;
    fireEvent.pointerMove(slider, { pointerId: 7, pointerType: 'touch', clientX: 151, clientY: 18 });
    now += 40;
    fireEvent.pointerUp(slider, { pointerId: 7, pointerType: 'touch', clientX: 151, clientY: 18 });

    await waitFor(() => expect(requestVerification).toHaveBeenCalledTimes(1));
    const trackPayload = requestVerification.mock.calls[0]?.[0].track;
    expect(trackPayload[0]).toEqual({ x: 0, y: 0, elapsed_ms: 0 });
    expect(trackPayload.at(-1).x).toBe(500);
    expect(trackPayload.at(-1).x).toBeLessThan(970);
    expect(trackPayload.at(-1).elapsed_ms).toBe(360);
    expect(trackPayload.every((point: { y: number }) => point.y >= -100 && point.y <= 100)).toBe(true);
    expect(onVerified).toHaveBeenCalledWith('pointer-proof');
  });

  it('意外丢失 pointer capture 时复位，正常 pointerup 后不会干扰核验', async () => {
    const requestVerification = vi.fn();
    render(
      <LoginSliderCaptcha
        resetKey={0}
        onVerified={vi.fn()}
        requestChallenge={vi.fn().mockResolvedValue(CHALLENGE)}
        requestVerification={requestVerification}
      />,
    );
    const slider = await screen.findByRole('slider');
    Object.defineProperty(slider, 'setPointerCapture', { value: vi.fn(), configurable: true });

    fireEvent.pointerDown(slider, { pointerId: 9, clientX: 20, clientY: 20 });
    fireEvent.lostPointerCapture(slider, { pointerId: 9 });

    expect(slider.getAttribute('aria-valuenow')).toBe('0');
    expect(screen.getAllByText('拖动滑块，让拼图对准缺口').length).toBeGreaterThan(0);
    expect(requestVerification).not.toHaveBeenCalled();
  });

  it('服务端判定拼图未匹配时自动刷新为新拼图', async () => {
    let now = 2500;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const requestChallenge = vi.fn()
      .mockResolvedValueOnce(CHALLENGE)
      .mockResolvedValueOnce(NEXT_CHALLENGE);
    const requestVerification = vi.fn().mockRejectedValue(new Error('INVALID_PARAM'));
    render(
      <LoginSliderCaptcha
        resetKey={0}
        onVerified={vi.fn()}
        requestChallenge={requestChallenge}
        requestVerification={requestVerification}
      />,
    );
    const slider = await screen.findByRole('slider');
    for (let index = 0; index < 20; index += 1) {
      now += 20;
      fireEvent.keyDown(slider, { key: 'ArrowRight' });
    }
    now += 20;
    fireEvent.keyDown(slider, { key: 'Enter' });

    await waitFor(() => expect(requestChallenge).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByAltText('带有随机缺口的验证码背景').getAttribute('src'))
      .toBe(NEXT_CHALLENGE.background_image));
    expect(screen.getAllByText('验证未通过，请重新拖动').length).toBeGreaterThan(0);
  });

  it('拒绝远程 URL、非 PNG 或空的图片载荷', async () => {
    const requestChallenge = vi.fn().mockResolvedValue({
      ...CHALLENGE,
      background_image: 'https://example.com/captcha.png',
      piece_image: 'data:image/svg+xml;base64,PHN2Zy8+',
    });
    render(
      <LoginSliderCaptcha
        resetKey={0}
        onVerified={vi.fn()}
        requestChallenge={requestChallenge}
        requestVerification={vi.fn()}
      />,
    );

    expect((await screen.findAllByText('验证加载失败，请点击重试')).length).toBeGreaterThan(0);
    expect(screen.queryByAltText('带有随机缺口的验证码背景')).toBeNull();
    expect(screen.getByRole('button', { name: /刷新拼图/ }).hasAttribute('disabled')).toBe(false);
  });

  it('外部 reset 后忽略旧 challenge 的在途 verify 响应', async () => {
    let now = 3000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    let resolveVerification: ((value: { captcha_proof: string; expires_in_seconds: number }) => void) | undefined;
    const requestVerification = vi.fn().mockReturnValue(new Promise((resolve) => {
      resolveVerification = resolve;
    }));
    const requestChallenge = vi.fn().mockResolvedValue(CHALLENGE);
    const onVerified = vi.fn();
    const view = render(
      <LoginSliderCaptcha
        resetKey={0}
        onVerified={onVerified}
        requestChallenge={requestChallenge}
        requestVerification={requestVerification}
      />,
    );
    const slider = await screen.findByRole('slider');
    for (let index = 0; index < 20; index += 1) {
      now += 20;
      fireEvent.keyDown(slider, { key: 'ArrowRight' });
    }
    now += 20;
    fireEvent.keyDown(slider, { key: 'Enter' });
    await waitFor(() => expect(requestVerification).toHaveBeenCalledOnce());

    view.rerender(
      <LoginSliderCaptcha
        resetKey={1}
        onVerified={onVerified}
        requestChallenge={requestChallenge}
        requestVerification={requestVerification}
      />,
    );
    await waitFor(() => expect(requestChallenge).toHaveBeenCalledTimes(2));
    await act(async () => {
      resolveVerification?.({ captcha_proof: 'stale-proof', expires_in_seconds: 60 });
    });

    expect(onVerified).not.toHaveBeenCalled();
  });

  it('外部 reset 后忽略晚到的旧 challenge 响应', async () => {
    let resolveFirst: ((value: typeof CHALLENGE) => void) | undefined;
    const requestChallenge = vi.fn()
      .mockReturnValueOnce(new Promise((resolve) => {
        resolveFirst = resolve;
      }))
      .mockResolvedValueOnce(NEXT_CHALLENGE);
    const view = render(
      <LoginSliderCaptcha
        resetKey={0}
        onVerified={vi.fn()}
        requestChallenge={requestChallenge}
        requestVerification={vi.fn()}
      />,
    );
    await waitFor(() => expect(requestChallenge).toHaveBeenCalledOnce());

    view.rerender(
      <LoginSliderCaptcha
        resetKey={1}
        onVerified={vi.fn()}
        requestChallenge={requestChallenge}
        requestVerification={vi.fn()}
      />,
    );
    await waitFor(() => expect(screen.getByAltText('带有随机缺口的验证码背景').getAttribute('src'))
      .toBe(NEXT_CHALLENGE.background_image));
    await act(async () => {
      resolveFirst?.(CHALLENGE);
    });

    expect(screen.getByAltText('带有随机缺口的验证码背景').getAttribute('src'))
      .toBe(NEXT_CHALLENGE.background_image);
  });
});

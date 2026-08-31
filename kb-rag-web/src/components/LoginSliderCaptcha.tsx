import { CheckOutlined, LoadingOutlined, ReloadOutlined, RightOutlined } from '@ant-design/icons';
import type { CSSProperties, KeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { createCaptchaChallenge, verifyCaptcha } from '../api/auth';
import type { CaptchaChallengeResponse, CaptchaTrackPoint, CaptchaVerifyRequest, CaptchaVerifyResponse } from '../api/types';
import './LoginSliderCaptcha.css';

const TRACK_SCALE = 1000;
const MIN_TRACK_DURATION_MS = 300;
const MAX_TRACK_DURATION_MS = 15_000;
const MAX_TRACK_POINTS = 200;
const KEYBOARD_STEPS = 50;
const THUMB_WIDTH_PX = 46;
const READY_TEXT = '拖动滑块，让拼图对准缺口';
const PNG_DATA_URL_PATTERN = /^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/;

type CaptchaPhase = 'loading' | 'ready' | 'dragging' | 'verifying' | 'verified';

interface PointerSession {
  pointerId: number;
  startX: number;
  startY: number;
  startedAt: number;
  points: CaptchaTrackPoint[];
}

interface KeyboardSession {
  startedAt: number;
  points: CaptchaTrackPoint[];
}

export interface LoginSliderCaptchaProps {
  disabled?: boolean;
  resetKey: number;
  onVerified: (captchaProof: string) => void;
  requestChallenge?: () => Promise<CaptchaChallengeResponse>;
  requestVerification?: (payload: CaptchaVerifyRequest) => Promise<CaptchaVerifyResponse>;
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(Math.max(value, minimum), maximum);
}

function appendPoint(points: CaptchaTrackPoint[], point: CaptchaTrackPoint): CaptchaTrackPoint[] {
  if (points.length >= MAX_TRACK_POINTS) {
    return [...points.slice(0, MAX_TRACK_POINTS - 1), point];
  }
  return [...points, point];
}

function elapsedSince(startedAt: number): number {
  return Math.max(0, Math.round(performance.now() - startedAt));
}

function isUsableChallenge(challenge: CaptchaChallengeResponse): boolean {
  return PNG_DATA_URL_PATTERN.test(challenge.background_image)
    && PNG_DATA_URL_PATTERN.test(challenge.piece_image)
    && challenge.track_scale > 0
    && challenge.image_width > 0
    && challenge.image_height > 0
    && challenge.piece_width > 0
    && challenge.piece_height > 0
    && challenge.piece_width <= challenge.image_width
    && challenge.piece_height <= challenge.image_height
    && challenge.piece_y >= 0
    && challenge.piece_y + challenge.piece_height <= challenge.image_height;
}

/** 登录页唯一的交互签名：采集真实指针/键盘轨迹并兑换一次性 proof。 */
export default function LoginSliderCaptcha({
  disabled = false,
  resetKey,
  onVerified,
  requestChallenge = createCaptchaChallenge,
  requestVerification = verifyCaptcha,
}: LoginSliderCaptchaProps) {
  const [challenge, setChallenge] = useState<CaptchaChallengeResponse | null>(null);
  const [phase, setPhase] = useState<CaptchaPhase>('loading');
  const [position, setPosition] = useState(0);
  const [feedback, setFeedback] = useState(READY_TEXT);
  const trackRef = useRef<HTMLDivElement>(null);
  const pointerSessionRef = useRef<PointerSession | null>(null);
  const keyboardSessionRef = useRef<KeyboardSession | null>(null);
  const positionRef = useRef(0);
  const verifyInFlightRef = useRef(false);
  const requestSequenceRef = useRef(0);

  const setCurrentPosition = useCallback((nextPosition: number) => {
    positionRef.current = nextPosition;
    setPosition(nextPosition);
  }, []);

  const resetInteraction = useCallback((nextFeedback = READY_TEXT) => {
    pointerSessionRef.current = null;
    keyboardSessionRef.current = null;
    verifyInFlightRef.current = false;
    setCurrentPosition(0);
    setPhase('ready');
    setFeedback(nextFeedback);
  }, [setCurrentPosition]);

  const loadChallenge = useCallback(async (nextFeedback = READY_TEXT) => {
    const sequence = ++requestSequenceRef.current;
    pointerSessionRef.current = null;
    keyboardSessionRef.current = null;
    verifyInFlightRef.current = false;
    setChallenge(null);
    setCurrentPosition(0);
    setPhase('loading');
    setFeedback('正在准备安全验证…');
    try {
      const response = await requestChallenge();
      if (requestSequenceRef.current !== sequence) {
        return;
      }
      if (!isUsableChallenge(response)) {
        throw new Error('Invalid captcha image payload');
      }
      setChallenge(response);
      setPhase('ready');
      setFeedback(nextFeedback);
    } catch {
      if (requestSequenceRef.current === sequence) {
        setPhase('ready');
        setFeedback('验证加载失败，请点击重试');
      }
    }
  }, [requestChallenge, setCurrentPosition]);

  useEffect(() => {
    void loadChallenge();
    return () => {
      requestSequenceRef.current += 1;
    };
  }, [loadChallenge, resetKey]);

  const submitTrack = useCallback(async (track: CaptchaTrackPoint[]) => {
    if (!challenge || verifyInFlightRef.current || disabled) {
      return;
    }
    const lastPoint = track.at(-1);
    if (
      track.length < 3
      || !lastPoint
      || lastPoint.elapsed_ms < MIN_TRACK_DURATION_MS
      || lastPoint.elapsed_ms > MAX_TRACK_DURATION_MS
    ) {
      resetInteraction('请平稳拖动至少 0.3 秒后再完成验证');
      return;
    }

    verifyInFlightRef.current = true;
    const verificationSequence = requestSequenceRef.current;
    setPhase('verifying');
    setFeedback('正在核验滑动轨迹…');
    try {
      const response = await requestVerification({ challenge_id: challenge.challenge_id, track });
      if (requestSequenceRef.current !== verificationSequence) {
        return;
      }
      setPhase('verified');
      setFeedback('验证通过，正在登录…');
      onVerified(response.captcha_proof);
    } catch {
      if (requestSequenceRef.current === verificationSequence) {
        await loadChallenge('验证未通过，请重新拖动');
      }
    } finally {
      if (requestSequenceRef.current === verificationSequence) {
        verifyInFlightRef.current = false;
      }
    }
  }, [challenge, disabled, loadChallenge, onVerified, requestVerification, resetInteraction]);

  const calculatePointerPoint = useCallback((clientX: number, clientY: number, session: PointerSession) => {
    const trackWidth = trackRef.current?.getBoundingClientRect().width ?? 0;
    const travel = Math.max(trackWidth - THUMB_WIDTH_PX, 1);
    const scale = challenge?.track_scale ?? TRACK_SCALE;
    return {
      x: Math.round(clamp((clientX - session.startX) / travel, 0, 1) * scale),
      y: Math.round(clamp(((clientY - session.startY) / travel) * scale, -100, 100)),
      elapsed_ms: elapsedSince(session.startedAt),
    } satisfies CaptchaTrackPoint;
  }, [challenge?.track_scale]);

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!challenge || disabled || phase !== 'ready') {
      return;
    }
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    pointerSessionRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startedAt: performance.now(),
      points: [{ x: 0, y: 0, elapsed_ms: 0 }],
    };
    keyboardSessionRef.current = null;
    setPhase('dragging');
    setFeedback('观察拼图位置，在缺口处释放');
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const session = pointerSessionRef.current;
    if (!session || session.pointerId !== event.pointerId || phase !== 'dragging') {
      return;
    }
    event.preventDefault();
    const point = calculatePointerPoint(event.clientX, event.clientY, session);
    if (session.points.length < MAX_TRACK_POINTS - 1) {
      session.points = appendPoint(session.points, point);
    }
    setCurrentPosition(point.x);
  };

  const finishPointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    const session = pointerSessionRef.current;
    if (!session || session.pointerId !== event.pointerId) {
      return;
    }
    event.preventDefault();
    const finalPoint = calculatePointerPoint(event.clientX, event.clientY, session);
    const track = appendPoint(session.points, finalPoint);
    pointerSessionRef.current = null;
    setCurrentPosition(finalPoint.x);
    void submitTrack(track);
  };

  const cancelPointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (pointerSessionRef.current?.pointerId !== event.pointerId) {
      return;
    }
    resetInteraction();
  };

  const handleLostPointerCapture = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (pointerSessionRef.current?.pointerId === event.pointerId) {
      resetInteraction();
    }
  };

  const recordKeyboardPosition = (nextPosition: number): CaptchaTrackPoint[] => {
    const now = performance.now();
    const scale = challenge?.track_scale ?? TRACK_SCALE;
    const session = keyboardSessionRef.current ?? {
      startedAt: now,
      points: [{ x: 0, y: 0, elapsed_ms: 0 }],
    };
    const point = {
      x: clamp(Math.round(nextPosition), 0, scale),
      y: 0,
      elapsed_ms: Math.max(0, Math.round(now - session.startedAt)),
    };
    session.points = appendPoint(session.points, point);
    keyboardSessionRef.current = session;
    setCurrentPosition(point.x);
    return session.points;
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!challenge || disabled || phase === 'loading' || phase === 'verifying' || phase === 'verified') {
      return;
    }
    const scale = challenge.track_scale;
    const keyboardStep = Math.max(Math.round(scale / KEYBOARD_STEPS), 1);
    if (event.key === 'ArrowRight' || event.key === 'ArrowUp') {
      event.preventDefault();
      recordKeyboardPosition(positionRef.current + keyboardStep);
      setFeedback('对准缺口后按 Enter 验证');
      return;
    }
    if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') {
      event.preventDefault();
      recordKeyboardPosition(positionRef.current - keyboardStep);
      return;
    }
    if (event.key === 'Home') {
      event.preventDefault();
      recordKeyboardPosition(0);
      return;
    }
    if (event.key === 'End') {
      event.preventDefault();
      recordKeyboardPosition(scale);
      setFeedback('对准缺口后按 Enter 或空格验证');
      return;
    }
    if (event.key !== 'Enter' && event.key !== ' ') {
      return;
    }
    event.preventDefault();
    const track = recordKeyboardPosition(positionRef.current);
    const elapsed = track.at(-1)?.elapsed_ms ?? 0;
    if (elapsed < MIN_TRACK_DURATION_MS) {
      setFeedback('请保持至少 0.3 秒，再按 Enter 完成验证');
      return;
    }
    void submitTrack(track);
  };

  const scale = challenge?.track_scale ?? TRACK_SCALE;
  const progress = clamp((position / scale) * 100, 0, 100);
  const pieceLeft = challenge
    ? (position / scale) * ((challenge.image_width - challenge.piece_width) / challenge.image_width) * 100
    : 0;
  const pieceTop = challenge ? (challenge.piece_y / challenge.image_height) * 100 : 0;
  const pieceWidth = challenge ? (challenge.piece_width / challenge.image_width) * 100 : 0;
  const pieceHeight = challenge ? (challenge.piece_height / challenge.image_height) * 100 : 0;
  const unavailable = disabled || !challenge || phase === 'loading' || phase === 'verifying' || phase === 'verified';
  const style = { '--login-captcha-progress': `${progress}%` } as CSSProperties;
  const ariaText = phase === 'verified' ? '验证通过' : `${Math.round(progress)}%，${feedback}`;

  return (
    <div className={`login-captcha login-captcha--${phase}${disabled ? ' is-disabled' : ''}`}>
      <div
        className="login-captcha__puzzle"
        data-testid="login-captcha-puzzle"
        style={{ aspectRatio: challenge ? `${challenge.image_width} / ${challenge.image_height}` : '2 / 1' }}
      >
        {challenge ? (
          <>
            <img
              className="login-captcha__background"
              src={challenge.background_image}
              alt="带有随机缺口的验证码背景"
              draggable={false}
            />
            <img
              className="login-captcha__piece"
              data-testid="login-captcha-piece"
              src={challenge.piece_image}
              alt=""
              draggable={false}
              style={{
                left: `${pieceLeft}%`,
                top: `${pieceTop}%`,
                width: `${pieceWidth}%`,
                height: `${pieceHeight}%`,
              }}
            />
          </>
        ) : phase === 'loading' ? (
          <LoadingOutlined className="login-captcha__puzzle-loading" spin aria-label="正在加载随机拼图" />
        ) : (
          <ReloadOutlined className="login-captcha__puzzle-loading" aria-label="随机拼图加载失败" />
        )}
      </div>
      <div
        ref={trackRef}
        className="login-captcha__track"
        data-testid="login-captcha-track"
        style={style}
      >
        <span className="login-captcha__progress" aria-hidden="true" />
        <span className="login-captcha__instruction" aria-hidden="true">{feedback}</span>
        <div
          className="login-captcha__thumb"
          role="slider"
          tabIndex={unavailable ? -1 : 0}
          aria-label="登录安全验证"
          aria-valuemin={0}
          aria-valuemax={scale}
          aria-valuenow={Math.round(position)}
          aria-valuetext={ariaText}
          aria-disabled={unavailable}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={finishPointer}
          onPointerCancel={cancelPointer}
          onLostPointerCapture={handleLostPointerCapture}
          onKeyDown={handleKeyDown}
        >
          {phase === 'loading' || phase === 'verifying'
            ? <LoadingOutlined spin />
            : phase === 'verified'
              ? <CheckOutlined />
              : <RightOutlined />}
        </div>
      </div>
      <div className="login-captcha__footer">
        <span className="login-captcha__status" aria-live="polite">{feedback}</span>
        <button
          className="login-captcha__refresh"
          type="button"
          disabled={disabled || phase === 'loading' || phase === 'verifying' || phase === 'verified'}
          onClick={() => void loadChallenge('已更换拼图，请重新匹配')}
        >
          <ReloadOutlined /> 刷新拼图
        </button>
      </div>
    </div>
  );
}

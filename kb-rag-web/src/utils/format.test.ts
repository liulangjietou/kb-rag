import { describe, expect, it } from 'vitest';
import { formatFileSize, formatPercent, formatScore } from './format';

describe('formatFileSize', () => {
  it('formats byte unit boundaries', () => {
    expect(formatFileSize(0)).toBe('0 B');
    expect(formatFileSize(1023)).toBe('1023 B');
    expect(formatFileSize(1536)).toBe('1.5 KB');
    expect(formatFileSize(1024 * 1024)).toBe('1.0 MB');
  });

  it('rejects invalid byte counts', () => {
    expect(formatFileSize(-1)).toBe('-');
    expect(formatFileSize(Number.NaN)).toBe('-');
    expect(formatFileSize(Number.POSITIVE_INFINITY)).toBe('-');
  });
});

describe('score formatting', () => {
  it('uses the display precision required by retrieval pages', () => {
    expect(formatScore(0.123456)).toBe('0.1235');
    expect(formatPercent(0.357)).toBe('36%');
  });
});

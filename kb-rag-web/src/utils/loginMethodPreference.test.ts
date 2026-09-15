import { describe, expect, it, vi } from 'vitest';
import { LOGIN_METHOD_KEY, loadLoginMethod, saveLoginMethod } from './loginMethodPreference';

describe('loginMethodPreference', () => {
  it.each(['LOCAL', 'SSO'] as const)('只保存并恢复 %s 方式，不保存身份数据', (mode) => {
    const values = new Map<string, string>();
    const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value) };
    saveLoginMethod(mode, storage);
    expect(loadLoginMethod(storage)).toBe(mode);
    expect([...values]).toEqual([[LOGIN_METHOD_KEY, mode]]);
  });

  it.each([null, '', 'OIDC', '{"mode":"LOCAL","password":"secret"}'])('忽略不支持的存储值 %s', (value) => {
    expect(loadLoginMethod({ getItem: () => value })).toBeNull();
  });

  it('读写被浏览器拒绝时不会阻断认证', () => {
    const blocked = vi.fn(() => { throw new Error('Storage denied'); });
    expect(loadLoginMethod({ getItem: blocked })).toBeNull();
    expect(() => saveLoginMethod('LOCAL', { setItem: blocked })).not.toThrow();
  });
});

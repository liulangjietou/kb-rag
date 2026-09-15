import { describe, expect, it } from 'vitest';
import { authReturnLocation } from './returnLocation';

describe('认证回跳地址边界', () => {
  it.each([
    undefined, null, {}, { from: null }, { from: { pathname: 42 } },
    ...['https://other.invalid/workspace', '//other.invalid', String.raw`/\other.invalid`,
      '//[', '/\n/[', 'workspace', '/login', '/change-password/', '/a/../login', '/register',
      '/workspace?app=unexpected'].map((pathname) => ({ from: { pathname } })),
    { from: { pathname: '/workspace', search: 'unprefixed' } },
    { from: { pathname: '/workspace', hash: 'unprefixed' } },
  ])('无效目标 %j 回到默认首页路由', (state) => {
    expect(authReturnLocation(state)).toEqual({ pathname: '/', search: '', hash: '' });
  });

  it('保留站内参数中的编码字符，仅返回地址字段', () => {
    expect(authReturnLocation({ from: { pathname: '/workspace', search: '?app=a%26b&conversation=c%2Fd',
      hash: '#latest', state: { private: 'discarded' }, key: 'discarded' } })).toEqual({
      pathname: '/workspace', search: '?app=a%26b&conversation=c%2Fd', hash: '#latest',
    });
  });
});

import type { LoginMode } from '../api/types';

export const LOGIN_METHOD_KEY = 'kb-rag.login-method.v1';

/** 只恢复成功使用过的账号方式；配置是否允许该方式由登录页判断。 */
export function loadLoginMethod(storage?: Pick<Storage, 'getItem'>): LoginMode | null {
  try {
    const value = (storage ?? window.localStorage).getItem(LOGIN_METHOD_KEY);
    return value === 'LOCAL' || value === 'SSO' ? value : null;
  } catch {
    return null;
  }
}

/** 认证成功后保存界面偏好，不包含用户名、密码或令牌。 */
export function saveLoginMethod(mode: LoginMode, storage?: Pick<Storage, 'setItem'>): void {
  try {
    (storage ?? window.localStorage).setItem(LOGIN_METHOD_KEY, mode);
  } catch {
    // 浏览器禁止存储时仍应继续进入工作台。
  }
}

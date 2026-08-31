import type { LoginMode } from '../api/types';

const LOGIN_MEMORY_KEY = 'kb-rag.login-memory.v1';

type UsernameByMode = Partial<Record<LoginMode, string>>;

export interface LoginMemory {
  remember: boolean;
  usernames: UsernameByMode;
}

type LoginMemoryStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

const EMPTY_LOGIN_MEMORY: LoginMemory = { remember: false, usernames: {} };

/** localStorage 属于不可信输入，只在这一处收敛字段与类型。 */
export function loadLoginMemory(storage: LoginMemoryStorage = window.localStorage): LoginMemory {
  try {
    const raw = storage.getItem(LOGIN_MEMORY_KEY);
    if (!raw) {
      return EMPTY_LOGIN_MEMORY;
    }
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const usernames = typeof parsed.usernames === 'object' && parsed.usernames !== null
      ? parsed.usernames as Record<string, unknown>
      : {};
    const remember = parsed.remember === true;
    return {
      remember,
      usernames: remember
        ? {
            ...(typeof usernames.LOCAL === 'string' ? { LOCAL: usernames.LOCAL } : {}),
            ...(typeof usernames.SSO === 'string' ? { SSO: usernames.SSO } : {}),
          }
        : {},
    };
  } catch {
    return EMPTY_LOGIN_MEMORY;
  }
}

/** 只保存勾选偏好和用户名；此接口从类型上就不接收密码。 */
export function saveLoginMemory(memory: LoginMemory, storage: LoginMemoryStorage = window.localStorage): void {
  try {
    storage.setItem(LOGIN_MEMORY_KEY, JSON.stringify({
      remember: memory.remember,
      usernames: memory.remember ? memory.usernames : {},
    }));
  } catch {
    // 隐私模式或存储配额不足不应阻断登录。
  }
}

export function clearLoginMemory(storage: LoginMemoryStorage = window.localStorage): void {
  try {
    storage.removeItem(LOGIN_MEMORY_KEY);
  } catch {
    // 清理失败时保持静默，登录本身仍可继续。
  }
}

type PasswordCredentialConstructor = new (data: {
  id: string;
  name: string;
  password: string;
}) => Credential;

interface CredentialEnvironment {
  PasswordCredential?: PasswordCredentialConstructor;
  credentials?: Pick<CredentialsContainer, 'store'>;
}

/** 成功登录后把凭据交给浏览器密码管理器；不支持或被用户拒绝时静默降级。 */
export async function storePasswordCredential(
  username: string,
  password: string,
  environment?: CredentialEnvironment,
): Promise<void> {
  const runtime = environment ?? (typeof window === 'undefined'
    ? {}
    : {
        PasswordCredential: (window as Window & { PasswordCredential?: PasswordCredentialConstructor }).PasswordCredential,
        credentials: navigator.credentials,
      });
  if (!runtime.PasswordCredential || typeof runtime.credentials?.store !== 'function') {
    return;
  }
  try {
    const credential = new runtime.PasswordCredential({ id: username, name: username, password });
    await runtime.credentials.store(credential);
  } catch {
    // 用户拒绝保存或浏览器实现不完整，不影响已经完成的认证。
  }
}

export { LOGIN_MEMORY_KEY };

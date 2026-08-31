import { describe, expect, it, vi } from 'vitest';
import { LOGIN_MEMORY_KEY, loadLoginMemory, saveLoginMemory, storePasswordCredential } from './loginMemory';

function createStorage(initial?: string) {
  const values = new Map<string, string>();
  if (initial) {
    values.set(LOGIN_MEMORY_KEY, initial);
  }
  return {
    getItem: vi.fn((key: string) => values.get(key) ?? null),
    setItem: vi.fn((key: string, value: string) => values.set(key, value)),
    removeItem: vi.fn((key: string) => values.delete(key)),
    values,
  };
}

describe('loginMemory', () => {
  it('只读取允许的偏好和用户名字段，忽略任何持久化密码', () => {
    const storage = createStorage(JSON.stringify({
      remember: true,
      password: 'must-not-survive',
      usernames: { LOCAL: 'richard', SSO: 'domain-user', password: 'also-forbidden' },
    }));

    const memory = loadLoginMemory(storage);

    expect(memory).toEqual({
      remember: true,
      usernames: { LOCAL: 'richard', SSO: 'domain-user' },
    });
    expect(JSON.stringify(memory)).not.toContain('must-not-survive');
    expect(JSON.stringify(memory)).not.toContain('also-forbidden');
  });

  it('关闭记住选项时不会留下用户名或密码字段', () => {
    const storage = createStorage();

    saveLoginMemory({ remember: false, usernames: { LOCAL: 'richard' } }, storage);

    expect(JSON.parse(storage.values.get(LOGIN_MEMORY_KEY) ?? '{}')).toEqual({
      remember: false,
      usernames: {},
    });
  });

  it('仅在浏览器提供 Credential Management API 时交由密码管理器保存', async () => {
    const storedCredential = { id: 'richard' } as Credential;
    const PasswordCredential = vi.fn(function FakePasswordCredential() {
      return storedCredential;
    });
    const store = vi.fn().mockResolvedValue(storedCredential);

    await storePasswordCredential('richard', 'secret', {
      PasswordCredential: PasswordCredential as unknown as new (data: {
        id: string;
        name: string;
        password: string;
      }) => Credential,
      credentials: { store } as unknown as Pick<CredentialsContainer, 'store'>,
    });

    expect(PasswordCredential).toHaveBeenCalledWith({ id: 'richard', name: 'richard', password: 'secret' });
    expect(store).toHaveBeenCalledWith(storedCredential);

    await expect(storePasswordCredential('richard', 'secret', {})).resolves.toBeUndefined();
  });
});

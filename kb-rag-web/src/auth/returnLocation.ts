import type { Path } from 'react-router-dom';

const LOCAL_ORIGIN = 'https://auth-return.invalid';
const AUTH_PATHS = new Set(['/login', '/register', '/change-password']);
const DEFAULT_LOCATION: Path = { pathname: '/', search: '', hash: '' };

/** 从路由状态恢复站内地址；无目标、外部地址和认证页循环统一交给首页路由。 */
export function authReturnLocation(state: unknown): Path {
  if (!state || typeof state !== 'object' || !('from' in state)) return DEFAULT_LOCATION;
  const from = state.from;
  if (!from || typeof from !== 'object' || !('pathname' in from)) return DEFAULT_LOCATION;
  const { pathname } = from;
  if (typeof pathname !== 'string' || !pathname.startsWith('/') || pathname.includes('\\')) {
    return DEFAULT_LOCATION;
  }
  let url: URL;
  try {
    url = new URL(pathname, LOCAL_ORIGIN);
  } catch {
    return DEFAULT_LOCATION;
  }
  if (url.origin !== LOCAL_ORIGIN || url.search || url.hash || AUTH_PATHS.has(url.pathname.replace(/\/+$/, ''))) {
    return DEFAULT_LOCATION;
  }
  const search = 'search' in from && typeof from.search === 'string' ? from.search : '';
  const hash = 'hash' in from && typeof from.hash === 'string' ? from.hash : '';
  if ((search && !search.startsWith('?')) || (hash && !hash.startsWith('#'))) return DEFAULT_LOCATION;
  return { pathname: url.pathname, search, hash };
}

// Author: owlzhangfq@gmail.com
// The console's navigation model: one list that both the sider menu and the router's landing
// redirect read. Keeping it in one place is what makes "the menu shows exactly the screens this
// account can open" true by construction instead of by two lists agreeing with each other.
import {
  ApartmentOutlined,
  ApiOutlined,
  AppstoreOutlined,
  AuditOutlined,
  BulbOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  HomeOutlined,
  MessageOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';
import { PERMISSIONS } from '../auth/permissions';

export interface NavEntry {
  /** Route path, also the menu key. */
  key: string;
  label: string;
  icon: ReactNode;
  section: NavSection;
  /**
   * 允许进入菜单的权限码，任一满足即可，与服务端 @RequiresPermission 语义一致。列表必须映射
   * 页面真实接口权限，避免可见菜单首次请求必然返回 403；空列表表示仅要求登录，例如由各组件
   * 自行裁剪请求与内容的 /home。
   */
  anyOf: string[];
}

export type NavSection = 'workspace' | 'platform';

export const NAV_SECTIONS: Array<{ key: NavSection; label: string }> = [
  { key: 'workspace', label: '知识工作台' },
  { key: 'platform', label: '平台管理' },
];

export const NAV_ENTRIES: NavEntry[] = [
  { key: '/home', icon: <HomeOutlined />, label: '首页', section: 'workspace', anyOf: [] },
  { key: '/kb', icon: <DatabaseOutlined />, label: '知识库', section: 'workspace', anyOf: [PERMISSIONS.KB_READ] },
  { key: '/search', icon: <SearchOutlined />, label: '检索调试', section: 'workspace', anyOf: [PERMISSIONS.SEARCH_DEBUG] },
  {
    key: '/chat',
    icon: <MessageOutlined />,
    label: '问答调试',
    section: 'workspace',
    anyOf: [PERMISSIONS.APP_READ, PERMISSIONS.SEARCH_DEBUG],
  },
  { key: '/apps', icon: <AppstoreOutlined />, label: '应用中心', section: 'workspace', anyOf: [PERMISSIONS.APP_READ] },
  { key: '/memory', icon: <BulbOutlined />, label: '记忆库', section: 'workspace', anyOf: [PERMISSIONS.MEMORY_READ] },
  {
    key: '/mcp',
    icon: <ApiOutlined />,
    label: 'MCP 调试',
    section: 'workspace',
    anyOf: [PERMISSIONS.APP_READ, PERMISSIONS.MEMORY_READ],
  },
  { key: '/eval', icon: <ExperimentOutlined />, label: '评测中心', section: 'workspace', anyOf: [PERMISSIONS.EVAL_READ] },
  { key: '/users', icon: <TeamOutlined />, label: '用户管理', section: 'platform', anyOf: [PERMISSIONS.USER_MANAGE] },
  {
    key: '/roles',
    icon: <SafetyCertificateOutlined />,
    label: '角色管理',
    section: 'platform',
    anyOf: [PERMISSIONS.ROLE_MANAGE],
  },
  {
    key: '/settings/tenants',
    icon: <ApartmentOutlined />,
    label: '租户管理',
    section: 'platform',
    anyOf: [PERMISSIONS.TENANT_MANAGE],
  },
  {
    key: '/settings/operation-audits',
    icon: <AuditOutlined />,
    label: '操作审计',
    section: 'platform',
    anyOf: [PERMISSIONS.AUDIT_READ],
  },
  { key: '/settings', icon: <SettingOutlined />, label: '系统设置', section: 'platform', anyOf: [PERMISSIONS.SYSTEM_CONFIG] },
];

/** Path a session lands on when it asks for "/" or for something that does not exist. */
export const NO_ACCESS_PATH = '/no-access';

export function visibleNavEntries(canAny: (codes: string[]) => boolean): NavEntry[] {
  return NAV_ENTRIES.filter((entry) => entry.anyOf.length === 0 || canAny(entry.anyOf));
}

/**
 * 决定没有指定页面的会话落点。
 *
 * 首页是第一个仅要求登录的入口；最小权限账号也可使用，且每个请求和组件都按权限裁剪，
 * 因此不会产生重定向循环或无意义的 403 探测。
 */
export function landingPath(canAny: (codes: string[]) => boolean): string {
  return visibleNavEntries(canAny)[0]?.key ?? '/home';
}

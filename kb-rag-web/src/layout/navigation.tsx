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
  /**
   * Codes that admit the entry, any one of them being enough -- the same rule the server's
   * @RequiresPermission applies. The list mirrors what the screen's endpoints actually declare, so a
   * visible entry never opens onto a page that answers 403 on its first fetch.
   */
  anyOf: string[];
}

export const NAV_ENTRIES: NavEntry[] = [
  { key: '/kb', icon: <DatabaseOutlined />, label: '知识库', anyOf: [PERMISSIONS.KB_READ] },
  { key: '/search', icon: <SearchOutlined />, label: '检索调试', anyOf: [PERMISSIONS.SEARCH_DEBUG] },
  {
    key: '/chat',
    icon: <MessageOutlined />,
    label: '问答调试',
    anyOf: [PERMISSIONS.APP_READ, PERMISSIONS.SEARCH_DEBUG],
  },
  { key: '/apps', icon: <AppstoreOutlined />, label: '应用中心', anyOf: [PERMISSIONS.APP_READ] },
  { key: '/memory', icon: <BulbOutlined />, label: '记忆库', anyOf: [PERMISSIONS.MEMORY_READ] },
  {
    key: '/mcp',
    icon: <ApiOutlined />,
    label: 'MCP 调试',
    anyOf: [PERMISSIONS.APP_READ, PERMISSIONS.MEMORY_READ],
  },
  { key: '/eval', icon: <ExperimentOutlined />, label: '评测中心', anyOf: [PERMISSIONS.EVAL_READ] },
  { key: '/users', icon: <TeamOutlined />, label: '用户管理', anyOf: [PERMISSIONS.USER_MANAGE] },
  {
    key: '/roles',
    icon: <SafetyCertificateOutlined />,
    label: '角色管理',
    anyOf: [PERMISSIONS.ROLE_MANAGE],
  },
  {
    key: '/settings/tenants',
    icon: <ApartmentOutlined />,
    label: '租户管理',
    anyOf: [PERMISSIONS.TENANT_MANAGE],
  },
  {
    key: '/settings/operation-audits',
    icon: <AuditOutlined />,
    label: '操作审计',
    anyOf: [PERMISSIONS.AUDIT_READ],
  },
  { key: '/settings', icon: <SettingOutlined />, label: '系统设置', anyOf: [PERMISSIONS.SYSTEM_CONFIG] },
];

/** Path a session lands on when it asks for "/" or for something that does not exist. */
export const NO_ACCESS_PATH = '/no-access';

export function visibleNavEntries(canAny: (codes: string[]) => boolean): NavEntry[] {
  return NAV_ENTRIES.filter((entry) => canAny(entry.anyOf));
}

/**
 * Where to send a session that has not asked for a particular screen.
 *
 * The first entry it may open, in menu order, rather than a hardcoded /kb: an account granted only
 * evaluation rights would otherwise be bounced to a knowledge base list it is not allowed to read and
 * from there back to the root, which is a redirect loop rather than a landing page. A session with
 * nothing granted gets told so instead of being spun.
 */
export function landingPath(canAny: (codes: string[]) => boolean): string {
  return visibleNavEntries(canAny)[0]?.key ?? NO_ACCESS_PATH;
}

// Author: owlzhangfq@gmail.com
// Mirror of the server's PermissionCodes constants (kb-domain). Kept as a frozen map rather than
// scattered string literals so a renamed code breaks the build here too, instead of quietly hiding a
// menu entry nobody notices is missing.
//
// The console uses these to decide what to render. That is presentation only: every endpoint checks
// the same code again through @RequiresPermission, so a user who forges this list gains a visible
// button and a 403 behind it.

export const PERMISSIONS = {
  KB_READ: 'kb:read',
  KB_WRITE: 'kb:write',
  KB_DELETE: 'kb:delete',
  DOC_WRITE: 'doc:write',
  DOC_REVIEW: 'doc:review',
  SEARCH_DEBUG: 'search:debug',
  FEEDBACK_MANAGE: 'feedback:manage',
  EVAL_READ: 'eval:read',
  EVAL_WRITE: 'eval:write',
  EVAL_RUN: 'eval:run',
  APP_READ: 'app:read',
  APP_WRITE: 'app:write',
  APP_RELEASE: 'app:release',
  APIKEY_MANAGE: 'apikey:manage',
  AUDIT_READ: 'audit:read',
  SYSTEM_CONFIG: 'system:config',
  USER_MANAGE: 'user:manage',
  ROLE_MANAGE: 'role:manage',
  TENANT_MANAGE: 'tenant:manage',
} as const;

export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];

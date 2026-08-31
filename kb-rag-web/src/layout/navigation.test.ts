import { describe, expect, it } from 'vitest';
import { PERMISSIONS } from '../auth/permissions';
import { landingPath, NAV_SECTIONS, visibleNavEntries } from './navigation';

function permissionChecker(granted: string[]) {
  const permissionSet = new Set(granted);
  return (codes: string[]) => codes.some((code) => permissionSet.has(code));
}

describe('navigation model', () => {
  it('keeps workspace and platform entries in their declared sections', () => {
    const entries = visibleNavEntries(() => true);
    expect(NAV_SECTIONS.map((section) => section.key)).toEqual(['workspace', 'platform']);
    expect(entries.filter((entry) => entry.section === 'workspace')).toHaveLength(7);
    expect(entries.filter((entry) => entry.section === 'platform')).toHaveLength(5);
  });

  it('filters entries with any-of permission semantics', () => {
    const entries = visibleNavEntries(permissionChecker([PERMISSIONS.APP_READ]));
    expect(entries.map((entry) => entry.key)).toEqual(['/chat', '/apps', '/mcp']);
  });

  it('lands on the first permitted route and falls back to no-access', () => {
    expect(landingPath(permissionChecker([PERMISSIONS.EVAL_READ]))).toBe('/eval');
    expect(landingPath(permissionChecker([PERMISSIONS.SYSTEM_CONFIG]))).toBe('/settings');
    expect(landingPath(permissionChecker([]))).toBe('/no-access');
  });
});

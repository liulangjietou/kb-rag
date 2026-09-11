// Author: owlzhangfq@gmail.com
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
    expect(NAV_SECTIONS.map((section) => section.key)).toEqual(['overview', 'assets', 'build', 'platform']);
    expect(entries.filter((entry) => entry.section === 'overview').map((entry) => entry.key)).toEqual(['/home', '/workspace']);
    expect(entries.filter((entry) => entry.section === 'assets')).toHaveLength(2);
    expect(entries.filter((entry) => entry.section === 'build')).toHaveLength(5);
    expect(entries.filter((entry) => entry.section === 'platform')).toHaveLength(5);
  });

  it('filters entries with any-of permission semantics', () => {
    const entries = visibleNavEntries(permissionChecker([PERMISSIONS.APP_READ]));
    expect(entries.map((entry) => entry.key)).toEqual(['/home', '/apps', '/chat', '/mcp']);
  });

  it('员工使用权限只开放首页与正式问答', () => {
    expect(visibleNavEntries(permissionChecker([PERMISSIONS.APP_USE])).map((entry) => entry.key)).toEqual(['/home', '/workspace']);
  });

  it('lands every authenticated account on home regardless of its functional grants', () => {
    expect(landingPath(permissionChecker([PERMISSIONS.EVAL_READ]))).toBe('/home');
    expect(landingPath(permissionChecker([PERMISSIONS.SYSTEM_CONFIG]))).toBe('/home');
    expect(landingPath(permissionChecker([]))).toBe('/home');
  });
});

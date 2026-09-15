import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';
import { THEME_PRESETS, THEME_STORAGE_KEY } from '../src/theme/presets';
import { readStoredThemePreset, resolveThemePresetStorageChange } from '../src/theme/themeStorage';

describe('requested theme compatibility', () => {
  it('includes warm, dawn and both requested dark palettes', () => {
    for (const [id, name, mode] of [
      ['ember', 'Ember 暖焰', 'light'],
      ['dawn', 'Dawn 晨曦', 'light'],
      ['aurora', 'Aurora 极光', 'dark'],
      ['graphite-dark', 'Graphite 墨岩（深色）', 'dark'],
    ]) {
      expect(THEME_PRESETS.find(preset => preset.id === id), id).toMatchObject({ name, mode });
    }
  });

  it('retains the system preference instead of replacing it with a fixed palette', () => {
    expect(readStoredThemePreset({ getItem: () => 'system', setItem: () => {} })).toBe('system');
    expect(resolveThemePresetStorageChange(THEME_STORAGE_KEY, 'system')).toBe('system');
  });

  for (const dark of [false, true]) {
    it(`applies the saved system preference before React starts: dark=${dark}`, () => {
      const root = { dataset: {} as Record<string, string>, style: { colorScheme: '' } };
      runInNewContext(readFileSync(new URL('../public/theme-bootstrap.js', import.meta.url), 'utf8'), {
        document: { documentElement: root },
        localStorage: { getItem: () => 'system' },
        window: { matchMedia: () => ({ matches: dark }) },
      });
      expect(root.dataset.themePreference).toBe('system');
      expect(root.dataset.theme).toBe(dark ? 'night' : 'atlas');
      expect(root.style.colorScheme).toBe(dark ? 'dark' : 'light');
    });
  }
});

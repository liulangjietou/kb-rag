import { describe, expect, it } from 'vitest';
import { theme as antdTheme } from 'antd';
import {
  DEFAULT_THEME_PRESET_ID,
  THEME_PRESETS,
  THEME_STORAGE_KEY,
  createAntThemeConfig,
  getNextThemePresetId,
  resolveThemePresetId,
  type ThemePalette,
} from './presets';
import { applyThemePresetToDocument } from './applyTheme';
import {
  readStoredThemePreset,
  getBrowserThemeStorage,
  resolveThemePresetStorageChange,
  writeStoredThemePreset,
  type ThemeStorage,
} from './themeStorage';

function expectedCssVariables(palette: ThemePalette): Record<string, string> {
  return {
    '--kb-color-primary': palette.primary,
    '--kb-color-primary-hover': palette.primaryHover,
    '--kb-color-primary-active': palette.primaryActive,
    '--kb-color-primary-soft': palette.primarySoft,
    '--kb-color-bg': palette.background,
    '--kb-color-surface': palette.surface,
    '--kb-color-surface-raised': palette.surfaceRaised,
    '--kb-color-surface-subtle': palette.surfaceSubtle,
    '--kb-color-text': palette.text,
    '--kb-color-text-secondary': palette.textSecondary,
    '--kb-color-text-tertiary': palette.textTertiary,
    '--kb-color-border': palette.border,
    '--kb-color-border-strong': palette.borderStrong,
    '--kb-color-sidebar': palette.sidebar,
    '--kb-color-sidebar-text': palette.sidebarText,
    '--kb-color-sidebar-muted': palette.sidebarMuted,
    '--kb-color-sidebar-active': palette.sidebarActive,
    '--kb-color-success': palette.success,
    '--kb-color-warning': palette.warning,
    '--kb-color-danger': palette.danger,
    '--kb-color-info': palette.info,
    '--kb-focus-ring': palette.focusRing,
    '--kb-shadow': palette.shadow,
    '--kb-shadow-strong': palette.shadowStrong,
  };
}

function relativeLuminance(hex: string): number {
  const channels = hex
    .slice(1)
    .match(/.{2}/g)
    ?.map((channel) => Number.parseInt(channel, 16) / 255)
    .map((channel) => (channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4));
  if (!channels || channels.length !== 3) {
    throw new Error(`Invalid hex color: ${hex}`);
  }
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

function contrastRatio(first: string, second: string): number {
  const lighter = Math.max(relativeLuminance(first), relativeLuminance(second));
  const darker = Math.min(relativeLuminance(first), relativeLuminance(second));
  return (lighter + 0.05) / (darker + 0.05);
}

describe('theme presets', () => {
  it('provides four distinct presets with Atlas as the default', () => {
    expect(THEME_PRESETS.map((preset) => preset.id)).toEqual(['atlas', 'ocean', 'violet', 'night']);
    expect(new Set(THEME_PRESETS.map((preset) => preset.id)).size).toBe(THEME_PRESETS.length);
    expect(DEFAULT_THEME_PRESET_ID).toBe('atlas');
    expect(THEME_PRESETS.find((preset) => preset.id === 'night')?.mode).toBe('dark');
  });

  it('falls back to Atlas for missing or invalid persisted values', () => {
    expect(resolveThemePresetId(null)).toBe('atlas');
    expect(resolveThemePresetId('')).toBe('atlas');
    expect(resolveThemePresetId('unknown-theme')).toBe('atlas');
    expect(resolveThemePresetId('night')).toBe('night');
  });

  it('cycles through every preset and wraps back to Atlas', () => {
    expect(getNextThemePresetId('atlas')).toBe('ocean');
    expect(getNextThemePresetId('ocean')).toBe('violet');
    expect(getNextThemePresetId('violet')).toBe('night');
    expect(getNextThemePresetId('night')).toBe('atlas');
  });

  it('keeps white text on every primary button above WCAG AA contrast', () => {
    for (const preset of THEME_PRESETS) {
      expect(contrastRatio(preset.palette.primary, '#FFFFFF'), preset.id).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(preset.palette.primaryHover, '#FFFFFF'), `${preset.id} hover`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('creates an Ant Design theme matching the preset mode and tokens', () => {
    for (const preset of THEME_PRESETS) {
      const config = createAntThemeConfig(preset);
      expect(config.token?.colorPrimary).toBe(preset.palette.primary);
      expect(config.cssVar).toEqual({ key: `kb-rag-${preset.id}`, prefix: 'kb-ant' });
      expect(config.algorithm).toBe(
        preset.mode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
      );
    }
  });

});

describe('theme preference storage', () => {
  it('reads and writes the stable storage key', () => {
    const values = new Map<string, string>();
    const storage: ThemeStorage = {
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
    };
    writeStoredThemePreset(storage, 'violet');
    expect(values.get(THEME_STORAGE_KEY)).toBe('violet');
    expect(readStoredThemePreset(storage)).toBe('violet');
  });

  it('fails safely when browser storage is unavailable', () => {
    const storage: ThemeStorage = {
      getItem: () => {
        throw new Error('storage disabled');
      },
      setItem: () => {
        throw new Error('storage disabled');
      },
    };
    expect(readStoredThemePreset(storage)).toBe('atlas');
    expect(() => writeStoredThemePreset(storage, 'night')).not.toThrow();
    expect(getBrowserThemeStorage()).toBeUndefined();
  });

  it('syncs the stable key and reset events while ignoring unrelated storage changes', () => {
    expect(resolveThemePresetStorageChange(THEME_STORAGE_KEY, 'night')).toBe('night');
    expect(resolveThemePresetStorageChange(THEME_STORAGE_KEY, 'invalid')).toBe('atlas');
    expect(resolveThemePresetStorageChange(null, null)).toBe('atlas');
    expect(resolveThemePresetStorageChange('unrelated-key', 'violet')).toBeUndefined();
  });
});

describe('theme document bridge', () => {
  it('publishes every preset, color scheme, and semantic CSS variable', () => {
    for (const preset of THEME_PRESETS) {
      const properties = new Map<string, string>();
      const documentStub = {
        documentElement: {
          dataset: {} as DOMStringMap,
          style: {
            colorScheme: '',
            setProperty: (name: string, value: string) => properties.set(name, value),
          },
        },
      } as unknown as Document;

      applyThemePresetToDocument(preset.id, documentStub);

      expect(documentStub.documentElement.dataset.theme).toBe(preset.id);
      expect(documentStub.documentElement.style.colorScheme).toBe(preset.mode);
      expect(Object.fromEntries(properties)).toEqual(expectedCssVariables(preset.palette));
    }
  });
});

import {
  DEFAULT_THEME_PRESET_ID,
  THEME_STORAGE_KEY,
  resolveThemePresetId,
  type ThemePresetId,
} from './presets';

export interface ThemeStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export function readStoredThemePreset(storage?: ThemeStorage | null): ThemePresetId {
  if (!storage) {
    return DEFAULT_THEME_PRESET_ID;
  }
  try {
    return resolveThemePresetId(storage.getItem(THEME_STORAGE_KEY));
  } catch {
    return DEFAULT_THEME_PRESET_ID;
  }
}

export function writeStoredThemePreset(storage: ThemeStorage | null | undefined, id: ThemePresetId): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(THEME_STORAGE_KEY, id);
  } catch {
    // 浏览器禁用本地存储时仍允许用户在当前会话切换主题。
  }
}

export function resolveThemePresetStorageChange(
  key: string | null,
  newValue: string | null,
): ThemePresetId | undefined {
  if (key !== THEME_STORAGE_KEY && key !== null) {
    return undefined;
  }
  return resolveThemePresetId(newValue);
}

export function getBrowserThemeStorage(): ThemeStorage | undefined {
  if (typeof window === 'undefined') {
    return undefined;
  }
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

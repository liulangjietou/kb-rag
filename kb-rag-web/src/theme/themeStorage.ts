import {
  DEFAULT_THEME_PRESET_ID,
  THEME_STORAGE_KEY,
} from './presets';
import { resolveThemePreference, type ThemePreference } from './themePreference';

export interface ThemeStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export function readStoredThemePreset(storage?: ThemeStorage | null): ThemePreference {
  if (!storage) {
    return DEFAULT_THEME_PRESET_ID;
  }
  try {
    return resolveThemePreference(storage.getItem(THEME_STORAGE_KEY));
  } catch {
    return DEFAULT_THEME_PRESET_ID;
  }
}

export function writeStoredThemePreset(storage: ThemeStorage | null | undefined, id: ThemePreference): void {
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
): ThemePreference | undefined {
  if (key !== THEME_STORAGE_KEY && key !== null) {
    return undefined;
  }
  return resolveThemePreference(newValue);
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

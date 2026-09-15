import { isThemePresetId, resolveThemePresetId, type ThemePresetId } from './presets';

/** 用户保存的选择与系统当前解析出的色板分开，避免丢失自动跟随意图。 */
export type ThemePreference = ThemePresetId | 'system';
export const SYSTEM_COLOR_SCHEME_QUERY = '(prefers-color-scheme: dark)';

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === 'system' || isThemePresetId(value);
}

export function resolveThemePreference(value: unknown): ThemePreference {
  return value === 'system' ? 'system' : resolveThemePresetId(value);
}

export function resolvePreferencePalette(preference: ThemePreference, systemDark: boolean): ThemePresetId {
  return preference === 'system' ? (systemDark ? 'night' : 'atlas') : preference;
}

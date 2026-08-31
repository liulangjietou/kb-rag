import type { ThemeConfig } from 'antd';
import { theme as antdTheme } from 'antd';

export const THEME_STORAGE_KEY = 'kb-rag-web:theme-preset';

export type ThemePresetId = 'atlas' | 'ocean' | 'violet' | 'night';
export type ThemeMode = 'light' | 'dark';

export interface ThemePalette {
  primary: string;
  primaryHover: string;
  primaryActive: string;
  primarySoft: string;
  background: string;
  surface: string;
  surfaceRaised: string;
  surfaceSubtle: string;
  text: string;
  textSecondary: string;
  textTertiary: string;
  border: string;
  borderStrong: string;
  sidebar: string;
  sidebarText: string;
  sidebarMuted: string;
  sidebarActive: string;
  success: string;
  warning: string;
  danger: string;
  info: string;
  focusRing: string;
  shadow: string;
  shadowStrong: string;
}

export interface ThemePreset {
  id: ThemePresetId;
  name: string;
  description: string;
  mode: ThemeMode;
  palette: ThemePalette;
}

export const THEME_PRESETS: readonly ThemePreset[] = [
  {
    id: 'atlas',
    name: 'Atlas 翡翠',
    description: '沉稳清晰的知识工作台',
    mode: 'light',
    palette: {
      primary: '#0A756A',
      primaryHover: '#08665D',
      primaryActive: '#064F49',
      primarySoft: '#DDF2EE',
      background: '#F3F7F5',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#EAF1EE',
      text: '#132E32',
      textSecondary: '#4B6264',
      textTertiary: '#708285',
      border: '#D8E3DF',
      borderStrong: '#B8CAC4',
      sidebar: '#112F32',
      sidebarText: '#F2F8F6',
      sidebarMuted: '#A9BFBA',
      sidebarActive: '#1C4849',
      success: '#287A55',
      warning: '#A95F18',
      danger: '#B42318',
      info: '#2767A8',
      focusRing: 'rgba(10, 117, 106, 0.24)',
      shadow: '0 10px 30px rgba(19, 46, 50, 0.08)',
      shadowStrong: '0 20px 50px rgba(19, 46, 50, 0.14)',
    },
  },
  {
    id: 'ocean',
    name: 'Ocean 深海',
    description: '理性专注的蓝色界面',
    mode: 'light',
    palette: {
      primary: '#2457A6',
      primaryHover: '#1E4B91',
      primaryActive: '#193E78',
      primarySoft: '#E2EBFA',
      background: '#F3F6FB',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#EAF0F9',
      text: '#182B45',
      textSecondary: '#50627A',
      textTertiary: '#738197',
      border: '#D8E1EE',
      borderStrong: '#B9C7DA',
      sidebar: '#172A45',
      sidebarText: '#F3F7FC',
      sidebarMuted: '#ACBAD0',
      sidebarActive: '#263F66',
      success: '#287A55',
      warning: '#A95F18',
      danger: '#B42318',
      info: '#2457A6',
      focusRing: 'rgba(36, 87, 166, 0.24)',
      shadow: '0 10px 30px rgba(24, 43, 69, 0.08)',
      shadowStrong: '0 20px 50px rgba(24, 43, 69, 0.15)',
    },
  },
  {
    id: 'violet',
    name: 'Violet 智紫',
    description: '富有创造力的智能界面',
    mode: 'light',
    palette: {
      primary: '#6C3FAA',
      primaryHover: '#5D3595',
      primaryActive: '#4E2B7D',
      primarySoft: '#EFE6F8',
      background: '#F7F4FA',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#F0EBF5',
      text: '#342642',
      textSecondary: '#665873',
      textTertiary: '#867A90',
      border: '#E3DCE9',
      borderStrong: '#CBBFD5',
      sidebar: '#30233D',
      sidebarText: '#FAF6FD',
      sidebarMuted: '#C5B7CF',
      sidebarActive: '#4B3760',
      success: '#287A55',
      warning: '#A95F18',
      danger: '#B42318',
      info: '#315F9F',
      focusRing: 'rgba(108, 63, 170, 0.24)',
      shadow: '0 10px 30px rgba(52, 38, 66, 0.08)',
      shadowStrong: '0 20px 50px rgba(52, 38, 66, 0.15)',
    },
  },
  {
    id: 'night',
    name: 'Night 夜航',
    description: '低眩光的深色工作环境',
    mode: 'dark',
    palette: {
      primary: '#0F766E',
      primaryHover: '#0B655F',
      primaryActive: '#07534E',
      primarySoft: '#153C3A',
      background: '#101819',
      surface: '#182224',
      surfaceRaised: '#202C2E',
      surfaceSubtle: '#263335',
      text: '#EEF5F3',
      textSecondary: '#B8C7C4',
      textTertiary: '#8FA19E',
      border: '#354547',
      borderStrong: '#4C5E60',
      sidebar: '#0C1415',
      sidebarText: '#F2F8F6',
      sidebarMuted: '#92A6A2',
      sidebarActive: '#173E3C',
      success: '#58A77B',
      warning: '#D79A55',
      danger: '#E16B63',
      info: '#6B9FD7',
      focusRing: 'rgba(83, 184, 169, 0.32)',
      shadow: '0 12px 34px rgba(0, 0, 0, 0.28)',
      shadowStrong: '0 24px 60px rgba(0, 0, 0, 0.42)',
    },
  },
] as const;

export const DEFAULT_THEME_PRESET_ID: ThemePresetId = 'atlas';

const THEME_PRESET_IDS = new Set<string>(THEME_PRESETS.map((preset) => preset.id));

export function isThemePresetId(value: unknown): value is ThemePresetId {
  return typeof value === 'string' && THEME_PRESET_IDS.has(value);
}

export function resolveThemePresetId(value: unknown): ThemePresetId {
  return isThemePresetId(value) ? value : DEFAULT_THEME_PRESET_ID;
}

export function getThemePreset(id: ThemePresetId): ThemePreset {
  return THEME_PRESETS.find((preset) => preset.id === id) ?? THEME_PRESETS[0];
}

export function getNextThemePresetId(current: ThemePresetId): ThemePresetId {
  const currentIndex = THEME_PRESETS.findIndex((preset) => preset.id === current);
  const nextIndex = (currentIndex + 1) % THEME_PRESETS.length;
  return THEME_PRESETS[nextIndex].id;
}

export function createAntThemeConfig(preset: ThemePreset): ThemeConfig {
  const { palette } = preset;
  return {
    algorithm: preset.mode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    cssVar: {
      key: `kb-rag-${preset.id}`,
      prefix: 'kb-ant',
    },
    hashed: false,
    token: {
      colorPrimary: palette.primary,
      colorPrimaryHover: palette.primaryHover,
      colorPrimaryActive: palette.primaryActive,
      colorInfo: palette.info,
      colorSuccess: palette.success,
      colorWarning: palette.warning,
      colorError: palette.danger,
      colorBgBase: palette.background,
      colorTextBase: palette.text,
      colorLink: palette.primary,
      colorLinkHover: palette.primaryHover,
      colorLinkActive: palette.primaryActive,
      colorBorder: palette.border,
      colorBorderSecondary: palette.border,
      colorBgContainer: palette.surface,
      colorBgElevated: palette.surfaceRaised,
      colorFillAlter: palette.surfaceSubtle,
      fontFamily:
        'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
      fontSize: 14,
      borderRadius: 10,
      borderRadiusLG: 16,
      controlHeight: 38,
      controlHeightLG: 44,
      lineWidth: 1,
      motionDurationFast: '0.12s',
      motionDurationMid: '0.2s',
      boxShadow: palette.shadow,
      boxShadowSecondary: palette.shadowStrong,
    },
    components: {
      Button: {
        borderRadius: 10,
        controlHeight: 38,
        fontWeight: 600,
        primaryShadow: 'none',
        defaultShadow: 'none',
      },
      Card: {
        borderRadiusLG: 16,
        headerBg: 'transparent',
      },
      Input: {
        activeBorderColor: palette.primary,
        hoverBorderColor: palette.primary,
        activeShadow: `0 0 0 3px ${palette.focusRing}`,
      },
      Select: {
        activeBorderColor: palette.primary,
        hoverBorderColor: palette.primary,
        activeOutlineColor: palette.focusRing,
      },
      Menu: {
        itemBg: 'transparent',
        subMenuItemBg: 'transparent',
        itemBorderRadius: 10,
        itemHeight: 42,
      },
      Table: {
        headerBg: palette.surfaceSubtle,
        headerColor: palette.textSecondary,
        headerSplitColor: palette.border,
        borderColor: palette.border,
        rowHoverBg: palette.primarySoft,
      },
      Tabs: {
        inkBarColor: palette.primary,
        itemSelectedColor: palette.primary,
        itemHoverColor: palette.primaryHover,
      },
      Layout: {
        bodyBg: palette.background,
        headerBg: palette.surface,
        siderBg: palette.sidebar,
      },
      Modal: {
        contentBg: palette.surfaceRaised,
        headerBg: palette.surfaceRaised,
        titleColor: palette.text,
      },
    },
  };
}

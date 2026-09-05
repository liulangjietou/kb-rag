import type { ThemeConfig } from 'antd';
import { theme as antdTheme } from 'antd';

export const THEME_STORAGE_KEY = 'kb-rag-web:theme-preset';

export type ThemePresetId =
  | 'atlas'
  | 'ocean'
  | 'violet'
  | 'cinder'
  | 'moss'
  | 'rose'
  | 'graphite'
  | 'night';
export type ThemeMode = 'light' | 'dark';

export interface ThemePalette {
  primary: string;
  primaryText: string;
  primaryHover: string;
  primaryActive: string;
  primarySoft: string;
  background: string;
  surface: string;
  surfaceRaised: string;
  surfaceSubtle: string;
  codeBackground: string;
  codeText: string;
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
      primary: '#176B5B',
      primaryText: '#176B5B',
      primaryHover: '#125A4C',
      primaryActive: '#104B40',
      primarySoft: '#E8F3EF',
      background: '#F5F7FA',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#F2F5F8',
      codeBackground: '#10282B',
      codeText: '#E3F0ED',
      text: '#202D3D',
      textSecondary: '#5F6C7D',
      textTertiary: '#5F6C7D',
      border: '#DFE5EC',
      borderStrong: '#B8C4D2',
      sidebar: '#F2F5F8',
      sidebarText: '#202D3D',
      sidebarMuted: '#5F6C7D',
      sidebarActive: '#E8F3EF',
      success: '#287A55',
      warning: '#94510F',
      danger: '#B42318',
      info: '#235C98',
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
      primaryText: '#2457A6',
      primaryHover: '#1E4B91',
      primaryActive: '#193E78',
      primarySoft: '#E2EBFA',
      background: '#F3F6FB',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#EAF0F9',
      codeBackground: '#12263F',
      codeText: '#E7F0FC',
      text: '#182B45',
      textSecondary: '#50627A',
      textTertiary: '#50627A',
      border: '#D8E1EE',
      borderStrong: '#B9C7DA',
      sidebar: '#EAF0F9',
      sidebarText: '#182B45',
      sidebarMuted: '#50627A',
      sidebarActive: '#E2EBFA',
      success: '#287A55',
      warning: '#94510F',
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
      primaryText: '#6C3FAA',
      primaryHover: '#5D3595',
      primaryActive: '#4E2B7D',
      primarySoft: '#EFE6F8',
      background: '#F7F4FA',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#F0EBF5',
      codeBackground: '#281E34',
      codeText: '#F3ECF8',
      text: '#342642',
      textSecondary: '#665873',
      textTertiary: '#665873',
      border: '#E3DCE9',
      borderStrong: '#CBBFD5',
      sidebar: '#F0EBF5',
      sidebarText: '#342642',
      sidebarMuted: '#665873',
      sidebarActive: '#EFE6F8',
      success: '#287A55',
      warning: '#94510F',
      danger: '#B42318',
      info: '#315F9F',
      focusRing: 'rgba(108, 63, 170, 0.24)',
      shadow: '0 10px 30px rgba(52, 38, 66, 0.08)',
      shadowStrong: '0 20px 50px rgba(52, 38, 66, 0.15)',
    },
  },
  {
    id: 'cinder',
    name: 'Cinder 灰烬',
    description: '温暖克制的研究与整理氛围',
    mode: 'light',
    palette: {
      primary: '#9A3412',
      primaryText: '#9A3412',
      primaryHover: '#7C2D12',
      primaryActive: '#5C1F0B',
      primarySoft: '#FCE8DD',
      background: '#FAF7F2',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#F2E9DE',
      codeBackground: '#2F211B',
      codeText: '#F8EEE8',
      text: '#2E211C',
      textSecondary: '#635046',
      textTertiary: '#635046',
      border: '#E5D8CD',
      borderStrong: '#CBB9AB',
      sidebar: '#F2E9DE',
      sidebarText: '#2E211C',
      sidebarMuted: '#635046',
      sidebarActive: '#FCE8DD',
      success: '#287A55',
      warning: '#94510F',
      danger: '#B42318',
      info: '#2F629A',
      focusRing: 'rgba(154, 52, 18, 0.30)',
      shadow: '0 10px 30px rgba(46, 33, 28, 0.08)',
      shadowStrong: '0 20px 50px rgba(46, 33, 28, 0.15)',
    },
  },
  {
    id: 'moss',
    name: 'Moss 苔原',
    description: '安静耐看的长期知识维护',
    mode: 'light',
    palette: {
      primary: '#4A5D23',
      primaryText: '#4A5D23',
      primaryHover: '#3D4D1D',
      primaryActive: '#2E3A16',
      primarySoft: '#E7EED4',
      background: '#F5F7EF',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#EDF1E3',
      codeBackground: '#202D24',
      codeText: '#EEF5E9',
      text: '#222D20',
      textSecondary: '#566150',
      textTertiary: '#566150',
      border: '#D9E1D0',
      borderStrong: '#BAC7AE',
      sidebar: '#EDF1E3',
      sidebarText: '#222D20',
      sidebarMuted: '#566150',
      sidebarActive: '#E7EED4',
      success: '#2E7650',
      warning: '#8C5A11',
      danger: '#B42318',
      info: '#2E6197',
      focusRing: 'rgba(74, 93, 35, 0.30)',
      shadow: '0 10px 30px rgba(34, 45, 32, 0.08)',
      shadowStrong: '0 20px 50px rgba(34, 45, 32, 0.15)',
    },
  },
  {
    id: 'rose',
    name: 'Rose 绯樱',
    description: '柔和鲜明的审阅与标注界面',
    mode: 'light',
    palette: {
      primary: '#9F315B',
      primaryText: '#9F315B',
      primaryHover: '#86264A',
      primaryActive: '#6D1E3C',
      primarySoft: '#F8E1EA',
      background: '#FAF4F7',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#F3E9ED',
      codeBackground: '#32212A',
      codeText: '#FAEEF3',
      text: '#35242C',
      textSecondary: '#67545D',
      textTertiary: '#67545D',
      border: '#E5D7DD',
      borderStrong: '#CDBBC3',
      sidebar: '#F3E9ED',
      sidebarText: '#35242C',
      sidebarMuted: '#67545D',
      sidebarActive: '#F8E1EA',
      success: '#287A55',
      warning: '#985715',
      danger: '#B42318',
      info: '#315F9F',
      focusRing: 'rgba(159, 49, 91, 0.28)',
      shadow: '0 10px 30px rgba(53, 36, 44, 0.08)',
      shadowStrong: '0 20px 50px rgba(53, 36, 44, 0.15)',
    },
  },
  {
    id: 'graphite',
    name: 'Graphite 墨岩',
    description: '中性克制的密集数据工作台',
    mode: 'light',
    palette: {
      primary: '#3F5968',
      primaryText: '#3F5968',
      primaryHover: '#344A57',
      primaryActive: '#283B46',
      primarySoft: '#E4EBEE',
      background: '#F4F6F7',
      surface: '#FFFFFF',
      surfaceRaised: '#FFFFFF',
      surfaceSubtle: '#E9EEF0',
      codeBackground: '#1B252B',
      codeText: '#EDF3F5',
      text: '#202C33',
      textSecondary: '#56636A',
      textTertiary: '#56636A',
      border: '#D7DEE2',
      borderStrong: '#B8C4CA',
      sidebar: '#E9EEF0',
      sidebarText: '#202C33',
      sidebarMuted: '#56636A',
      sidebarActive: '#E4EBEE',
      success: '#287A55',
      warning: '#945A13',
      danger: '#B42318',
      info: '#315F9F',
      focusRing: 'rgba(63, 89, 104, 0.28)',
      shadow: '0 10px 30px rgba(32, 44, 51, 0.08)',
      shadowStrong: '0 20px 50px rgba(32, 44, 51, 0.15)',
    },
  },
  {
    id: 'night',
    name: 'Night 夜航',
    description: '低眩光的深色工作环境',
    mode: 'dark',
    palette: {
      primary: '#0F766E',
      primaryText: '#72D4C1',
      primaryHover: '#0B655F',
      primaryActive: '#07534E',
      primarySoft: '#153C3A',
      background: '#101819',
      surface: '#182224',
      surfaceRaised: '#202C2E',
      surfaceSubtle: '#263335',
      codeBackground: '#091112',
      codeText: '#E3F0ED',
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
      colorPrimaryText: palette.primaryText,
      colorPrimaryTextHover: palette.primaryText,
      colorPrimaryTextActive: palette.primaryText,
      colorInfo: palette.info,
      colorSuccess: palette.success,
      colorWarning: palette.warning,
      colorError: palette.danger,
      colorBgBase: palette.background,
      colorTextBase: palette.text,
      colorText: palette.text,
      colorTextSecondary: palette.textSecondary,
      colorTextTertiary: palette.textTertiary,
      colorLink: palette.primaryText,
      colorLinkHover: palette.primaryText,
      colorLinkActive: palette.primaryText,
      colorBorder: palette.border,
      colorBorderSecondary: palette.border,
      colorBgContainer: palette.surface,
      colorBgElevated: palette.surfaceRaised,
      colorFillAlter: palette.surfaceSubtle,
      fontFamily:
        'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
      fontSize: 14,
      borderRadius: 6,
      borderRadiusLG: 9,
      controlHeight: 36,
      controlHeightLG: 40,
      controlHeightSM: 30,
      lineWidth: 1,
      motionDurationFast: '0.12s',
      motionDurationMid: '0.2s',
      boxShadow: palette.shadow,
      boxShadowSecondary: palette.shadowStrong,
    },
    components: {
      Button: {
        borderRadius: 6,
        controlHeight: 36,
        fontWeight: 600,
        primaryShadow: 'none',
        defaultShadow: 'none',
      },
      Card: {
        borderRadiusLG: 9,
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
        itemBorderRadius: 6,
        itemHeight: 38,
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
        itemSelectedColor: palette.primaryText,
        itemHoverColor: palette.primaryText,
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

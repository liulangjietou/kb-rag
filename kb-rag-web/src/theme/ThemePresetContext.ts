import { createContext, useContext } from 'react';
import type { ThemeConfig } from 'antd';
import type { ThemePreset, ThemePresetId } from './presets';

export interface ThemePresetContextValue {
  preset: ThemePreset;
  presetId: ThemePresetId;
  presets: readonly ThemePreset[];
  antThemeConfig: ThemeConfig;
  selectPreset: (id: ThemePresetId) => void;
  cyclePreset: () => void;
}

export const ThemePresetContext = createContext<ThemePresetContextValue | null>(null);

export function useThemePreset(): ThemePresetContextValue {
  const context = useContext(ThemePresetContext);
  if (!context) {
    throw new Error('useThemePreset must be used within ThemePresetProvider');
  }
  return context;
}

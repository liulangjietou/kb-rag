import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { applyThemePresetToDocument } from './applyTheme';
import {
  THEME_PRESETS,
  createAntThemeConfig,
  getNextThemePresetId,
  getThemePreset,
  type ThemePresetId,
} from './presets';
import { ThemePresetContext, type ThemePresetContextValue } from './ThemePresetContext';
import {
  getBrowserThemeStorage,
  readStoredThemePreset,
  resolveThemePresetStorageChange,
  writeStoredThemePreset,
} from './themeStorage';

interface ThemePresetProviderProps {
  children: ReactNode;
}

function initialThemePresetId(): ThemePresetId {
  return readStoredThemePreset(getBrowserThemeStorage());
}

export function ThemePresetProvider({ children }: ThemePresetProviderProps) {
  const [presetId, setPresetId] = useState<ThemePresetId>(initialThemePresetId);
  const preset = useMemo(() => getThemePreset(presetId), [presetId]);
  const antThemeConfig = useMemo(() => createAntThemeConfig(preset), [preset]);

  const selectPreset = useCallback((id: ThemePresetId) => {
    setPresetId(id);
  }, []);

  const cyclePreset = useCallback(() => {
    setPresetId((current) => getNextThemePresetId(current));
  }, []);

  useLayoutEffect(() => {
    applyThemePresetToDocument(presetId);
    writeStoredThemePreset(getBrowserThemeStorage(), presetId);
  }, [presetId]);

  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      const nextPresetId = resolveThemePresetStorageChange(event.key, event.newValue);
      if (!nextPresetId) {
        return;
      }
      setPresetId(nextPresetId);
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  const value = useMemo<ThemePresetContextValue>(
    () => ({
      preset,
      presetId,
      presets: THEME_PRESETS,
      antThemeConfig,
      selectPreset,
      cyclePreset,
    }),
    [antThemeConfig, cyclePreset, preset, presetId, selectPreset],
  );

  return <ThemePresetContext.Provider value={value}>{children}</ThemePresetContext.Provider>;
}

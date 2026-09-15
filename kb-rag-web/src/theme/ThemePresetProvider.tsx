import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { applyThemePresetToDocument } from './applyTheme';
import {
  THEME_PRESETS,
  createAntThemeConfig,
  getNextThemePresetId,
  getThemePreset,
} from './presets';
import { resolvePreferencePalette, SYSTEM_COLOR_SCHEME_QUERY, type ThemePreference } from './themePreference';
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

function initialThemePresetId(): ThemePreference {
  return readStoredThemePreset(getBrowserThemeStorage());
}

export function ThemePresetProvider({ children }: ThemePresetProviderProps) {
  const [reducedMotion, setReducedMotion] = useState(() => typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const [presetId, setPresetId] = useState<ThemePreference>(initialThemePresetId);
  const [systemDark, setSystemDark] = useState(() => typeof window !== 'undefined' && window.matchMedia(SYSTEM_COLOR_SCHEME_QUERY).matches);
  const preset = useMemo(() => getThemePreset(resolvePreferencePalette(presetId, systemDark)), [presetId, systemDark]);
  const antThemeConfig = useMemo(() => {
    const config = createAntThemeConfig(preset);
    return { ...config, token: { ...config.token, motion: !reducedMotion } };
  }, [preset, reducedMotion]);

  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)');
    const updateMotion = () => setReducedMotion(media.matches);
    media.addEventListener('change', updateMotion);
    return () => media.removeEventListener('change', updateMotion);
  }, []);

  useEffect(() => {
    const media = window.matchMedia(SYSTEM_COLOR_SCHEME_QUERY);
    const updateScheme = () => setSystemDark(media.matches);
    // 订阅后立即重读，覆盖首次渲染与 effect 之间的系统模式变更。
    media.addEventListener('change', updateScheme);
    updateScheme();
    return () => media.removeEventListener('change', updateScheme);
  }, []);

  const selectPreset = useCallback((id: ThemePreference) => {
    setPresetId(id);
  }, []);

  const cyclePreset = useCallback(() => {
    setPresetId((current) => current === 'system' ? 'atlas' : getNextThemePresetId(current));
  }, []);

  useLayoutEffect(() => {
    applyThemePresetToDocument(preset.id);
    document.documentElement.dataset.themePreference = presetId;
    writeStoredThemePreset(getBrowserThemeStorage(), presetId);
  }, [presetId, preset.id]);

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

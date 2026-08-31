import { getThemePreset, type ThemePalette, type ThemePresetId } from './presets';

type ThemeCssVariable = `--kb-${string}`;

function paletteToCssVariables(palette: ThemePalette): Readonly<Record<ThemeCssVariable, string>> {
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

export function applyThemePresetToDocument(id: ThemePresetId, targetDocument?: Document): void {
  const currentDocument = targetDocument ?? (typeof document === 'undefined' ? undefined : document);
  if (!currentDocument) {
    return;
  }
  const preset = getThemePreset(id);
  const root = currentDocument.documentElement;
  root.dataset.theme = preset.id;
  root.style.colorScheme = preset.mode;
  for (const [name, value] of Object.entries(paletteToCssVariables(preset.palette))) {
    root.style.setProperty(name, value);
  }
}

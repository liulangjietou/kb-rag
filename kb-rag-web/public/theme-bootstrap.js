(() => {
  const fallback = 'atlas';
  const modes = {
    atlas: 'light',
    ocean: 'light',
    violet: 'light',
    cinder: 'light',
    moss: 'light',
    rose: 'light',
    graphite: 'light',
    night: 'dark',
    ember: 'light',
    dawn: 'light',
    aurora: 'dark',
    'graphite-dark': 'dark',
  };
  try {
    const stored = localStorage.getItem('kb-rag-web:theme-preset');
    const preference = stored === 'system' || Object.prototype.hasOwnProperty.call(modes, stored) ? stored : fallback;
    const theme = preference === 'system'
      ? (typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'night' : 'atlas')
      : preference;
    document.documentElement.dataset.themePreference = preference;
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = modes[theme];
  } catch {
    document.documentElement.dataset.themePreference = fallback;
    document.documentElement.dataset.theme = fallback;
    document.documentElement.style.colorScheme = modes[fallback];
  }
})();

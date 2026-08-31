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
  };
  try {
    const stored = localStorage.getItem('kb-rag-web:theme-preset');
    const theme = Object.prototype.hasOwnProperty.call(modes, stored) ? stored : fallback;
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = modes[theme];
  } catch {
    document.documentElement.dataset.theme = fallback;
    document.documentElement.style.colorScheme = modes[fallback];
  }
})();

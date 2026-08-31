import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';
import { THEME_PRESETS } from '../src/theme/presets';

const bootstrap = readFileSync(new URL('../public/theme-bootstrap.js', import.meta.url), 'utf8');

function executeBootstrap(stored: string | null, storageError?: Error) {
  const documentElement = {
    dataset: {} as Record<string, string>,
    style: { colorScheme: '' },
  };
  const localStorage = {
    getItem: () => {
      if (storageError) {
        throw storageError;
      }
      return stored;
    },
  };

  runInNewContext(bootstrap, { document: { documentElement }, localStorage });
  return documentElement;
}

describe('theme bootstrap', () => {
  it('keeps pre-React modes and first-paint backgrounds aligned with every preset', () => {
    const indexHtml = readFileSync(new URL('../index.html', import.meta.url), 'utf8').toLowerCase();

    for (const preset of THEME_PRESETS) {
      expect(bootstrap).toContain(`${preset.id}: '${preset.mode}'`);
      expect(indexHtml).toContain(
        `html[data-theme='${preset.id}'] { background: ${preset.palette.background.toLowerCase()}; }`,
      );
    }
  });

  it('applies every stored preset before React starts', () => {
    for (const preset of THEME_PRESETS) {
      const root = executeBootstrap(preset.id);
      expect(root.dataset.theme).toBe(preset.id);
      expect(root.style.colorScheme).toBe(preset.mode);
    }
  });

  it('falls back to Atlas when storage is missing, invalid, or unavailable', () => {
    for (const stored of [null, '', 'unknown-theme', '__proto__']) {
      const root = executeBootstrap(stored);
      expect(root.dataset.theme).toBe('atlas');
      expect(root.style.colorScheme).toBe('light');
    }

    const root = executeBootstrap(null, new Error('storage disabled'));
    expect(root.dataset.theme).toBe('atlas');
    expect(root.style.colorScheme).toBe('light');
  });
});

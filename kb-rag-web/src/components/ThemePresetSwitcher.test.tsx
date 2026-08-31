// @vitest-environment jsdom
import { App as AntApp, ConfigProvider } from 'antd';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { THEME_STORAGE_KEY } from '../theme/presets';
import { ThemePresetProvider } from '../theme/ThemePresetProvider';
import ThemePresetSwitcher from './ThemePresetSwitcher';

function renderSwitcher() {
  return render(
    <ThemePresetProvider>
      <ConfigProvider>
        <AntApp>
          <ThemePresetSwitcher />
        </AntApp>
      </ConfigProvider>
    </ThemePresetProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
  class ResizeObserverMock {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.defineProperty(window, 'ResizeObserver', { configurable: true, value: ResizeObserverMock });
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, value: ResizeObserverMock });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ThemePresetSwitcher', () => {
  it('renders all presets and persists a selected new theme', async () => {
    const { unmount } = renderSwitcher();
    const chooser = screen.getByRole('button', { name: '选择界面主题' });

    fireEvent.click(chooser);
    const menu = await screen.findByRole('menu');
    expect(within(menu).getAllByRole('menuitem')).toHaveLength(8);
    expect(chooser.getAttribute('aria-controls')).toBe(menu.id);
    expect(chooser.getAttribute('aria-expanded')).toBe('true');

    fireEvent.click(within(menu).getByRole('menuitem', { name: /Graphite 墨岩/ }));
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('graphite'));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('graphite');
    expect(screen.getByRole('status').textContent).toContain('Graphite 墨岩');
    expect(screen.getByRole('button', { name: '当前为 Graphite 墨岩，切换到 Night 夜航' })).toBeTruthy();
    await waitFor(() => expect(chooser.getAttribute('aria-expanded')).toBe('false'));

    unmount();
    renderSwitcher();
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Graphite 墨岩'));
  });

  it('cycles from the final preset back to Atlas', async () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'night');
    renderSwitcher();

    fireEvent.click(screen.getByRole('button', { name: '当前为 Night 夜航，切换到 Atlas 翡翠' }));
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('atlas'));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('atlas');
  });
});

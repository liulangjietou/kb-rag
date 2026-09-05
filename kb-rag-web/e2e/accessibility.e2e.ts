import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';

for (const theme of ['atlas', 'ocean', 'violet', 'cinder', 'moss', 'rose', 'graphite', 'night']) {
  for (const route of ['/home', '/kb/kb_fixture', '/apps/app_fixture', '/search', '/chat']) {
    test(`${route} · ${theme} 可访问性`, async ({ page, unexpectedRequests }) => {
      await page.addInitScript((preset) => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
      await page.goto(route);
      await expect(page.locator('.page-header h1, .atlas-home__heading h1').first()).toBeVisible();
      await expect.poll(() => page.locator('.ant-spin-spinning').count()).toBe(0);
      const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(
        result.violations.map((violation) => ({
          id: violation.id,
          nodes: violation.nodes.map((node) => ({ target: node.target, detail: node.failureSummary })),
        })),
      ).toEqual([]);
      expect(unexpectedRequests).toEqual([]);
    });
  }
}

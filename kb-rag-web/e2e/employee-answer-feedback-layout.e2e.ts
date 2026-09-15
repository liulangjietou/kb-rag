import { THEME_IDS } from './theme-fixture';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';
import { employeeFixture, employeeUrl } from './employee-workspace-fixture';

test.use({ grantedPermissions: ['app:use'] });

for (const theme of THEME_IDS) {
  for (const [width, height] of [[390, 844], [768, 1024], [1280, 720], [1440, 900], [1920, 1080]]) {
    test(`回答反馈 · ${theme} · ${width}px`, async ({ page, unexpectedRequests }, testInfo) => {
      await page.setViewportSize({ width, height });
      await page.addInitScript((preset) => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
      await employeeFixture(page);
      await page.goto(employeeUrl);
      await page.getByRole('button', { name: '回答需改进' }).click();
      const dialog = page.getByRole('dialog', { name: '回答反馈' });
      await dialog.getByRole('textbox', { name: '问题说明' }).fill('需要补充原始制度的适用日期与例外条件，便于核对这次回答的依据。'.repeat(20).slice(0, 512));
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
      const result = await new AxeBuilder({ page }).include('.ant-modal-content').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(result.violations.map((violation) => ({ id: violation.id, targets: violation.nodes.map((node) => node.target) }))).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath('answer-feedback.png'), fullPage: true });
      await dialog.getByRole('textbox', { name: '问题说明' }).focus();
      await expect(dialog.getByRole('textbox', { name: '问题说明' })).toBeFocused();
      await page.keyboard.press('Escape');
      await expect(dialog).not.toBeVisible();
      await expect(page.getByRole('button', { name: '回答需改进' })).toBeFocused();
      expect(unexpectedRequests).toEqual([]);
    });
  }
}

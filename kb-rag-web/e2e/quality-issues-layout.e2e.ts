import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';
import { fixture, openIssue, fillCorrection } from './quality-issues-fixture';

for (const theme of ['atlas', 'ocean', 'violet', 'cinder', 'moss', 'rose', 'graphite', 'night']) {
  for (const [width, height] of [[390,844],[768,1024],[1280,720],[1440,900],[1920,1080]]) {
    test(`质量问题 · ${theme} · ${width}px`, async ({ page, api, unexpectedRequests }, testInfo) => {
      await page.setViewportSize({ width, height });
      await page.addInitScript((preset) => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
      await fixture(page, api); await openIssue(page);
      const dialog = page.getByRole('dialog', { name: '处理质量问题' });
      await page.getByRole('button', { name: '领取问题', exact: true }).click();
      await fillCorrection(page);
      await page.getByLabel('纠正说明', { exact: true }).fill('核对原始材料的适用范围和有效日期，保留完整的处理依据。'.repeat(12));
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
      expect(await dialog.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(0);
      const audit = await new AxeBuilder({ page }).include('.quality-issue-drawer').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
      expect(audit.violations.map((v) => ({ id:v.id, targets:v.nodes.map((n) => n.target) }))).toEqual([]);
      await page.screenshot({ path:testInfo.outputPath('quality-correction.png'), fullPage:true });
      await page.getByRole('button', { name:'取消编辑', exact:true }).focus();
      await page.keyboard.press('Enter');
      await page.getByRole('button', { name:'放弃更改', exact:true }).click();
      await page.getByRole('button', { name:'刷新状态', exact:true }).focus();
      await page.keyboard.press('Escape');
      await expect(dialog).not.toBeVisible();
      await expect(page.getByRole('button', { name:'查看处理', exact:true })).toBeFocused();
      expect(unexpectedRequests).toEqual([]);
    });
  }
}

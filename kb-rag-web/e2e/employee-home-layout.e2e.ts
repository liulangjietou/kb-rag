import AxeBuilder from '@axe-core/playwright';
import { test, expect, app } from './fixtures';
import { employeeConversation } from './employee-workspace-fixture';

test.use({ grantedPermissions: ['app:use'] });

for (const theme of ['atlas', 'ocean', 'violet', 'cinder', 'moss', 'rose', 'graphite', 'night']) {
  for (const [width, height] of [[390, 844], [768, 1024], [1280, 720], [1440, 900], [1920, 1080]]) {
    test(`员工首页 · ${theme} · ${width}px`, async ({ page, api, unexpectedRequests }, testInfo) => {
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await page.setViewportSize({ width, height });
      await page.addInitScript((preset) => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
      api['/workspace/overview'] = {
        applications: [{ ...app, name: '产品售后与复杂交付流程知识助手', description: '通过正式知识来源核验问题，处理信息不完整与跨部门协作场景。' }],
        recent_conversations: [{ ...employeeConversation, app_name: app.name, active_run_id: 'run_fixture',
          title: '需要多部门共同确认的售后服务、退换货与交付验收问题'.repeat(3) }],
      };
      await page.goto('/home');
      await expect(page.getByRole('button', { name: /需要多部门共同确认/ })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
      const panel = page.getByRole('region', { name: '知识问答' });
      const result = await new AxeBuilder({ page }).include('.employee-home').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(result.violations.map((violation) => ({ id: violation.id, targets: violation.nodes.map((node) => node.target) }))).toEqual([]);
      await panel.getByRole('button', { name: '刷新知识问答入口' }).focus();
      await expect(panel.getByRole('button', { name: '刷新知识问答入口' })).toBeFocused();
      expect(errors).toEqual([]);
      expect(unexpectedRequests).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath('employee-home.png'), fullPage: true });
    });
  }
}

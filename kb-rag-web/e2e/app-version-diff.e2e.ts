import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

async function openVersions(page: Page, api: Record<string, unknown>) {
  const config = { kb_refs: [{ kb_id: 'kb_fixture', weight: 1 }], retrieval: { recall_top_k: 50, top_n: 5 },
    prompt: { system_prompt: '请依据资料回答。', refusal_enabled: true, refusal_prompt: '', leak_guard_enabled: true, leak_guard_prompt: '', citation_enabled: true }, chat_model: 'model-before' };
  const common = { app_id: 'app_fixture', gate_dataset_id: null, gate_run_ids: [], gate_verdict: null, force_released: false,
    changelog: null, created_at: '2026-09-14T12:00:00', updated_at: '2026-09-14T12:00:00' };
  api['/apps/app_fixture/versions'] = [
    { ...common, app_version_id: 'candidate', version: 'V2.0', status: 'DRAFT', config: { ...config, chat_model: 'model-after',
      prompt: { ...config.prompt, system_prompt: '仅依据可信资料回答，证据不足时明确说明。'.repeat(12) } } },
    { ...common, app_version_id: 'baseline', version: 'V1.0', status: 'RELEASED', config },
  ];
  api['/app-versions/candidate/corpus-diff'] = {
    baseline: { source: 'FROZEN', complete: true, document_count: 2 }, candidate: { source: 'CURRENT', complete: true, document_count: 2 },
    comparable: true, total: 3, added: 1, removed: 1, updated: 1, page: 1, page_size: 20,
    items: [
      { kb_id: 'kb_fixture', doc_id: 'doc-new', file_name: '新增资料.md', change: 'ADDED', candidate: { version_id: 'dv-new', version: '1.0' } },
      { kb_id: 'kb_fixture', doc_id: 'doc-old', file_name: '移除资料.md', change: 'REMOVED', baseline: { version_id: 'dv-old', version: '1.0' } },
      { kb_id: 'kb_fixture', doc_id: 'doc-updated', file_name: '更新资料.md', change: 'UPDATED', baseline: { version_id: 'dv-before', version: '1.0' }, candidate: { version_id: 'dv-after', version: '2.0' } },
    ],
  };
  await page.goto('/apps/app_fixture');
  await page.getByRole('tab', { name: '版本与发布' }).click();
  const trigger = page.getByRole('row').filter({ hasText: 'V2.0' }).getByRole('button', { name: '版本差异', exact: true });
  await trigger.click();
  return trigger;
}

test('发布前对照配置与同数量但不同资料的真实集合差异，所有操作只读', async ({ page, api, unexpectedRequests }) => {
  await openVersions(page, api);
  const drawer = page.getByRole('dialog', { name: '版本差异' });
  await expect(drawer.getByText('model-before', { exact: true })).toBeVisible();
  await expect(drawer.getByText('model-after', { exact: true })).toBeVisible();
  await expect(drawer.getByText('当前资料（未冻结）', { exact: true })).toBeVisible();
  await expect(drawer.getByText('新增资料.md', { exact: true })).toBeVisible();
  await expect(drawer.getByText('移除资料.md', { exact: true })).toBeVisible();
  await expect(drawer.getByText('更新资料.md', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('历史资料缺失显示不可比，不冒充零差异', async ({ page, api, unexpectedRequests }) => {
  await openVersions(page, api);
  api['/app-versions/candidate/corpus-diff'] = { baseline: { source: 'UNAVAILABLE', complete: false },
    candidate: { source: 'CURRENT', complete: true, document_count: 2 }, comparable: false, page: 1, page_size: 20, items: [] };
  await page.getByRole('button', { name: '刷新差异', exact: true }).click();
  await expect(page.getByText('无法完整比较资料', { exact: true })).toBeVisible();
  await expect(page.getByText('历史快照不可用', { exact: true })).toBeVisible();
  await expect(page.getByText('当前可读取资料无差异', { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('资料刷新失败清空旧结果，恢复后可独立重试', async ({ page, api, unexpectedRequests }) => {
  await openVersions(page, api);
  await expect(page.getByText('新增资料.md', { exact: true })).toBeVisible();
  await page.route('**/api/v1/app-versions/candidate/corpus-diff?*', route => route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: '无权限' } }));
  await page.getByRole('button', { name: '刷新差异', exact: true }).click();
  await expect(page.getByText('资料差异读取失败', { exact: true })).toBeVisible();
  await expect(page.getByText('新增资料.md', { exact: true })).toHaveCount(0);
  await expect(page.getByText('model-after', { exact: true })).toBeVisible();
  await page.unroute('**/api/v1/app-versions/candidate/corpus-diff?*');
  await page.getByRole('button', { name: '重试资料差异', exact: true }).click();
  await expect(page.getByText('新增资料.md', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('刷新版本失败清空旧配置与资料，不显示无差异', async ({ page, api, unexpectedRequests }) => {
  await openVersions(page, api);
  await expect(page.getByText('model-after', { exact: true })).toBeVisible();
  await page.route('**/api/v1/apps/app_fixture/versions', route => route.fulfill({ status: 500, json: { code: 'INTERNAL_ERROR', message: 'unavailable' } }));
  await page.getByRole('button', { name: '刷新差异', exact: true }).click();
  await expect(page.getByText('版本差异加载失败', { exact: true })).toBeVisible();
  await expect(page.getByText('model-after', { exact: true })).toHaveCount(0);
  await expect(page.getByText('新增资料.md', { exact: true })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('历史门禁不可比提示关闭后恢复到原按钮', async ({ page, api, unexpectedRequests }) => {
  await openVersions(page, api);
  await page.getByRole('dialog', { name: '版本差异' }).getByRole('button', { name: '关闭', exact: true }).click();
  const rows = api['/apps/app_fixture/versions'] as Array<Record<string, unknown>>;
  rows[0].gate_run_ids = ['gate-candidate', 'gate-baseline'];
  api['/eval-runs/compare'] = { comparable: false, reason: '历史评测集版本不同', runs: [] };
  await page.getByRole('button', { name: '刷新版本', exact: true }).click();
  const trigger = page.getByRole('button', { name: '查看双跑结果', exact: true });
  await trigger.click();
  await expect(page.getByText('历史评测集版本不同', { exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
  expect(unexpectedRequests).toEqual([]);
});

for (const theme of ['atlas', 'ocean', 'violet', 'cinder', 'moss', 'rose', 'graphite', 'night']) {
  for (const width of [390, 768, 1280, 1440, 1920]) {
    test(`版本差异 · ${theme} · ${width}px`, async ({ page, api, unexpectedRequests }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.addInitScript(preset => localStorage.setItem('kb-rag-web:theme-preset', preset), theme);
      const trigger = await openVersions(page, api);
      await expect(page.getByText('model-after', { exact: true })).toBeVisible();
      await expect(page.getByText('更新资料.md', { exact: true })).toBeVisible();
      const bounds = await page.locator('.app-version-diff').boundingBox();
      expect(bounds).not.toBeNull();
      expect(bounds!.x).toBeGreaterThanOrEqual(0);
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
      const overflow = await page.locator('.app-version-diff').evaluate(el => el.scrollWidth > el.clientWidth + 1);
      expect(overflow).toBe(false);
      if (theme === 'atlas' && width === 390) {
        const audit = await new AxeBuilder({ page }).include('.ant-drawer-content').analyze();
        expect(audit.violations).toEqual([]);
      }
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog', { name: '版本差异' })).toHaveCount(0);
      await expect(trigger).toBeFocused();
      expect(unexpectedRequests).toEqual([]);
    });
  }
}

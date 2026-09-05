import { test, expect, pageData } from './fixtures';

const sections = [
  {
    route: '/kb/kb_fixture',
    tabs: ['数据来源', '外部数据源', '聊天导入', '知识图谱', '质量与反馈', '检索洞察', '回收站'],
  },
  { route: '/settings', tabs: ['检索词典', 'API Key', '站点凭据', '导入映射', '告警配置', 'API 调用日志'] },
  { route: '/memory/mem_fixture', tabs: ['用户画像规则', '记忆实体', '检索调试', 'Memory Key'] },
];

for (const width of [1440, 880, 390]) {
  for (const { route, tabs } of sections) {
    test(`${route} 二级功能 · ${width}px`, async ({ page, api, unexpectedRequests }, testInfo) => {
      Object.assign(api, {
        '/kb/kb_fixture/web-sources': pageData([]),
        '/kb/kb_fixture/ext-sources': pageData([]),
        '/kb/kb_fixture/graph/summary': {
          graph_enabled: false,
          entity_count: 0,
          relation_count: 0,
          covered_chunk_count: 0,
        },
        '/kb/kb_fixture/graph/entities': pageData([]),
        '/kb/kb_fixture/retrieval-feedback': pageData([]),
        '/kb/kb_fixture/search-insights': pageData([]),
        '/kb/kb_fixture/search-insights/stats': {
          total: 0,
          zero_hit_count: 0,
          zero_hit_rate: 0,
          degraded_count: 0,
          top_zero_hit_queries: [],
        },
        '/kb/kb_fixture/trash': pageData([]),
        '/dict/ik': pageData([]),
        '/api-keys': [],
        '/web-credentials': [],
        '/source-mappings': [],
        '/system/alert-config': {
          enabled: false,
          webhook_url: '',
          task_fail_threshold: 5,
          degrade_rate_threshold: 0.3,
          sync_backlog_threshold: 100,
          silence_minutes: 30,
        },
        '/api-audit-logs': pageData([]),
        '/api-audit-logs/stats': { total_calls: 0, avg_latency_ms: 0, degraded_calls: 0, error_calls: 0 },
        '/memory-libraries/mem_fixture/profile-rules': [],
        '/memory-libraries/mem_fixture/entities': pageData([]),
        '/memory-libraries/mem_fixture/keys': [],
      });
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      page.on('console', (message) => {
        if (message.type() === 'error') errors.push(message.text());
      });
      await page.setViewportSize({ width, height: 1000 });
      await page.goto(route);
      for (const tabName of tabs) {
        const tab = page.getByRole('tab', { name: new RegExp(`${tabName}$`) });
        const navigation = tab.locator('xpath=ancestor::*[@role="tablist"][1]');
        const bounds = await tab.boundingBox();
        const visibleBounds = await navigation.locator('.ant-tabs-nav-wrap').boundingBox();
        if (
          bounds &&
          visibleBounds &&
          (bounds.x < visibleBounds.x || bounds.x + bounds.width > visibleBounds.x + visibleBounds.width)
        ) {
          await navigation.locator('.ant-tabs-nav-more').click();
          await page.locator('.ant-tabs-dropdown-menu-item').filter({ hasText: tabName }).click();
        } else {
          await tab.click();
        }
        await expect(tab).toHaveAttribute('aria-selected', 'true');
        await expect.poll(() => page.locator('.ant-spin-spinning').count()).toBe(0);
        expect(
          await page.evaluate(() => document.documentElement.scrollWidth - innerWidth),
          tabName,
        ).toBeLessThanOrEqual(0);
        await page.screenshot({ path: testInfo.outputPath(`${tabName}.png`), fullPage: true });
      }
      expect(errors).toEqual([]);
      expect(unexpectedRequests).toEqual([]);
    });
  }
}

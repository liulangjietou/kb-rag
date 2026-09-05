import { test as base, expect } from '@playwright/test';
import { PERMISSIONS } from '../src/auth/permissions';

export const kb = {
  kb_id: 'kb_fixture',
  name: '产品与交付知识库',
  description: '产品说明、交付流程与服务规范',
  index_config: null,
  current_config_fingerprint: null,
  created_at: '2026-09-01T02:00:00Z',
  graph_enabled: false,
  review_required: true,
};
export const app = {
  app_id: 'app_fixture',
  name: '客户服务助手',
  description: '回答产品问题，并给出知识来源',
  released_version: 'v1.3',
  released_version_id: 'v13',
  created_at: '2026-09-01T02:00:00Z',
  updated_at: '2026-09-04T08:00:00Z',
};
export const memory = {
  library_id: 'mem_fixture',
  name: '服务对话记忆',
  description: '客户偏好与历史服务上下文',
  node_count: 128,
  entity_count: 24,
  fragment_rule_count: 2,
  profile_rule_count: 1,
  created_at: '2026-09-01T02:00:00Z',
  updated_at: '2026-09-04T08:00:00Z',
  fragment_rules: [],
  profile_rules: [],
};
export const documents = [
  '产品使用手册.pdf',
  '售后服务政策.docx',
  '交付验收清单.xlsx',
  '实施常见问题.md',
  '客户反馈汇总.csv',
].map((name, i) => ({
  doc_id: `doc_${i}`,
  kb_id: kb.kb_id,
  file_name: name,
  file_ext: name.split('.').at(-1),
  file_size: 182400,
  current_version_id: `v${i}`,
  process_status: i === 3 ? 'PARSE_FAILED' : 'INDEXED',
  publish_status: i === 1 ? 'PENDING_REVIEW' : 'PUBLISHED',
  config_stale: i === 4,
  fail_reason: i === 3 ? 'Document parser timed out' : null,
  review_note: null,
  effective_at: null,
  expires_at: null,
  trashed_at: null,
  restricted: false,
  created_at: '2026-09-04T08:00:00Z',
}));
export const pageData = (items: unknown[]) => ({ items, page: 1, size: 10, total: items.length });

export const test = base.extend<{
  grantedPermissions: string[];
  api: Record<string, unknown>;
  unexpectedRequests: string[];
}>({
  grantedPermissions: [Object.values(PERMISSIONS), { option: true }],
  api: async ({ grantedPermissions }, provide) => {
    await provide({
      '/auth/me': {
        username: 'ui-fixture@example.com',
        display_name: '测试用户',
        source: 'LOCAL',
        must_change_password: false,
        roles: [],
        permissions: grantedPermissions,
        kb_scope_all: true,
        kb_ids: [],
      },
      '/auth/sso-available': { sso_available: false },
      '/auth/sso/providers': { oidc: false, saml: false, cas: false },
      '/system/model-status': {
        embedding_configured: true,
        provider: 'dashscope',
        model: 'text-embedding-v4',
        dimension: 1024,
        rerank_configured: true,
        rerank_provider: 'dashscope',
        rerank_model: 'gte-rerank-v2',
        chat_configured: true,
        chat_provider: 'dashscope',
        chat_model: 'qwen-plus',
        vector_engine: 'elasticsearch',
        vision_configured: false,
        multimodal_configured: false,
      },
      '/system/demo/status': { available: false, installed: false },
      '/kb': [kb],
      '/kb/kb_fixture': kb,
      '/kb/kb_fixture/documents': pageData(documents),
      '/kb/kb_fixture/rebuild-status': { stale_count: 1, in_progress_count: 0, failed_count: 0 },
      '/apps': [app],
      '/apps/app_fixture': app,
      '/apps/app_fixture/versions': [],
      '/memory-libraries': pageData([memory]),
      '/memory-libraries/mem_fixture': memory,
      '/memory-libraries/mem_fixture/fragment-rules': [],
      '/kb/kb_fixture/eval-datasets': [],
      '/registration-reviews': pageData([]),
      '/users': pageData([]),
      '/roles': [],
      '/roles/permissions': [],
      '/tenants': [],
      '/operation-audits': pageData([]),
    });
  },
  unexpectedRequests: async ({ context, api }, provide) => {
    const unexpected: string[] = [];
    await context.addInitScript(() => localStorage.setItem('kb-rag-web:auth-token', 'ui-test-fixture'));
    await context.route('**/api/v1/**', async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname.replace('/api/v1', '');
      if (path === '/auth/captcha/challenge' && request.method() === 'POST') {
        const pixel =
          'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zg0sAAAAASUVORK5CYII=';
        return route.fulfill({
          json: {
            code: 'OK',
            data: {
              challenge_id: 'ui-only',
              expires_in_seconds: 120,
              track_scale: 1000,
              background_image: pixel,
              piece_image: pixel,
              image_width: 300,
              image_height: 150,
              piece_width: 50,
              piece_height: 50,
              piece_y: 50,
            },
          },
        });
      }
      if (request.method() !== 'GET' || !Object.hasOwn(api, path)) {
        unexpected.push(`${request.method()} ${path}`);
        return route.fulfill({
          status: 503,
          json: { code: 'FIXTURE_MISSING', message: 'Test API fixture missing', data: null },
        });
      }
      await route.fulfill({ json: { code: 'OK', message: 'success', data: api[path] } });
    });
    await provide(unexpected);
    expect(unexpected, '所有请求必须有明确的接口契约夹具').toEqual([]);
  },
});
export { expect };

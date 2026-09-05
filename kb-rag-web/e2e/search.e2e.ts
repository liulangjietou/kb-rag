import { test, expect, kb } from './fixtures';
import type { RetrievalNode, SearchResponse } from '../src/api/types';

const node: RetrievalNode = {
  doc_id: 'doc_0',
  document_version_id: 'v0',
  chunk_id: 'chunk_fixture',
  chunk_type: 'text',
  content: '产品自交付之日起提供一年的保修服务。',
  score: 0.91,
  score_type: 'rerank',
  retrieval_source: 'vector',
  metadata: { kb_id: kb.kb_id },
  image_urls: [],
  preview_url: null,
};
const result: SearchResponse = {
  nodes: [node],
  request_id: 'search_fixture',
  degraded: [],
  applied: { rewrite_used_query: null, fusion_mode: 'rrf', threshold_applied_on: 'rerank' },
};

async function chooseKb(page: import('@playwright/test').Page) {
  await page.getByRole('combobox', { name: '知识库（必填）', exact: true }).click();
  await page.locator('.ant-select-item-option').filter({ hasText: kb.name }).click();
}

test('折叠参数仍提交完整默认值，结果与引用证据保持一致', async ({ page, unexpectedRequests }) => {
  let payload: unknown;
  await page.route('**/api/v1/kb/kb_fixture/search', async (route) => {
    payload = route.request().postDataJSON();
    await route.fulfill({ json: { code: 'OK', data: result } });
  });
  await page.goto('/search');
  await chooseKb(page);
  await page.getByPlaceholder('输入需要检索的问题或关键词').fill('保修期多久');
  await page.getByRole('button', { name: '开始检索' }).click();
  await expect
    .poll(() => payload)
    .toEqual({
      query: '保修期多久',
      recall_top_k: 50,
      top_n: 5,
      rewrite_enabled: false,
      rerank_enabled: true,
      rerank_mode: 'semantic',
      score_threshold: null,
      fusion: { mode: 'rrf', rrf_k: 60 },
    });
  await expect(page.getByText(node.content, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '查看命中证据' }).click();
  await expect(page.getByRole('dialog', { name: '命中证据' })).toContainText(node.content);
  expect(unexpectedRequests).toEqual([]);
});

test('图谱知识库禁用加权融合，混合重排和过滤在收起后保留', async ({ page, api, unexpectedRequests }) => {
  api['/kb'] = [{ ...kb, graph_enabled: true }];
  let payload: unknown;
  await page.route('**/api/v1/kb/kb_fixture/search', async (route) => {
    payload = route.request().postDataJSON();
    await route.fulfill({ json: { code: 'OK', data: result } });
  });
  await page.goto('/search');
  await chooseKb(page);
  await page.getByPlaceholder('输入需要检索的问题或关键词').fill('保修流程');
  await page.getByRole('button', { name: /融合$/ }).click();
  await expect(page.getByRole('radio', { name: '加权归一化', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: /重排$/ }).click();
  await page.getByText('混合（hybrid）', { exact: true }).click();
  await expect(page.getByRole('radio', { name: '混合（hybrid）', exact: true })).toBeChecked();
  await page.getByRole('button', { name: /重排$/ }).click();
  await page.getByRole('button', { name: /过滤$/ }).click();
  await page.getByRole('switch', { name: '启用阈值过滤' }).check();
  await page.getByPlaceholder('按发送人精确匹配').fill('客服');
  await page.getByRole('button', { name: /过滤$/ }).click();
  await page.getByRole('button', { name: '开始检索' }).click();
  await expect
    .poll(() => payload)
    .toMatchObject({
      rerank_mode: 'hybrid',
      rerank_w_semantic: 0.7,
      score_threshold: 0.5,
      metadata_filter: { sender: '客服' },
      fusion: { mode: 'rrf' },
    });
  expect(unexpectedRequests).toEqual([]);
});

test('检索失败展示错误并保留输入，重试后恢复结果', async ({ page, unexpectedRequests }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  let attempt = 0;
  await page.route('**/api/v1/kb/kb_fixture/search', async (route) => {
    attempt += 1;
    await route.fulfill(
      attempt === 1
        ? { status: 503, json: { code: 'RETRIEVAL_UNAVAILABLE', message: '检索服务暂不可用' } }
        : { json: { code: 'OK', data: result } },
    );
  });
  await page.goto('/search');
  await chooseKb(page);
  const input = page.getByPlaceholder('输入需要检索的问题或关键词');
  await input.fill('保修期多久');
  await page.getByRole('button', { name: '开始检索' }).click();
  await expect(page.getByRole('alert').filter({ hasText: '检索失败' })).toBeVisible();
  await expect(input).toHaveValue('保修期多久');
  await page.getByRole('button', { name: '开始检索' }).click();
  await expect(page.getByText(node.content, { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

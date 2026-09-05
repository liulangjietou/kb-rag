import { test, expect, documents, kb, pageData } from './fixtures';
import type { Route } from '@playwright/test';
import { PERMISSIONS } from '../src/auth/permissions';

test.describe('只读文档的历史版本', () => {
  test.use({ grantedPermissions: [PERMISSIONS.KB_READ] });
  test('版本历史可以查看，不能激活或调用待审核标注接口', async ({ page, api, unexpectedRequests }) => {
    api['/documents/doc_0/versions'] = [
      {
        version_id: 'version_old',
        version: 'V1',
        status: 'ARCHIVED',
        content_hash: 'fixture-hash',
        created_at: '2026-09-01T00:00:00Z',
        changelog: '首次入库',
        active: false,
        chunk_count: 16,
        rollback_mode: 'INSTANT',
      },
    ];
    await page.goto('/kb/kb_fixture');
    await page.getByRole('button', { name: '产品使用手册.pdf 更多操作' }).click();
    await expect(
      page.getByRole('menuitem', { name: /重新处理|移入回收站|设置有效期|设置可见性|提交审核/ }),
    ).toHaveCount(0);
    await page.getByRole('menuitem', { name: '版本历史' }).click();
    await expect(page.getByText('首次入库', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /^激\s*活$/ })).toBeDisabled();
    expect(unexpectedRequests).toEqual([]);
  });
});

test('翻页后晚到的旧页轮询不能覆盖当前页，勾选不会跨页保留', async ({ page, unexpectedRequests }) => {
  let firstPageRequests = 0;
  let pendingPoll: Route | undefined;
  const secondPage = { ...documents[0], doc_id: 'doc_second_page', file_name: '第二页文档.pdf' };
  await page.route('**/api/v1/kb/kb_fixture/documents?*', async (route) => {
    const pageNumber = Number(new URL(route.request().url()).searchParams.get('page'));
    if (pageNumber === 1 && ++firstPageRequests === 2) {
      pendingPoll = route;
      return;
    }
    await route.fulfill({
      json: {
        code: 'OK',
        data: { items: pageNumber === 1 ? documents : [secondPage], page: pageNumber, size: 10, total: 11 },
      },
    });
  });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('checkbox', { name: '选择 产品使用手册.pdf' }).check();
  await expect.poll(() => Boolean(pendingPoll)).toBe(true);
  await page.locator('.ant-pagination-item-2').click();
  await expect(page.getByText('第二页文档.pdf', { exact: true })).toBeVisible();
  await expect(page.getByRole('alert').filter({ hasText: '已选中' })).toHaveCount(0);
  const lateResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/documents?') && new URL(response.url()).searchParams.get('page') === '1',
  );
  await pendingPoll!.fulfill({
    json: { code: 'OK', data: { items: documents, page: 1, size: 10, total: 11 } },
  });
  await lateResponse;
  // 等待浏览器处理响应及下一帧，避免在旧响应尚未提交状态时提前断言成功。
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
  await expect(page.locator('.ant-pagination-item-active')).toHaveText('2');
  await expect(page.getByText('第二页文档.pdf', { exact: true })).toBeVisible();
  expect(unexpectedRequests).toEqual([]);
});

test('上传抽屉提交真实文件字段，并展示服务端重复内容判断', async ({ page, unexpectedRequests }) => {
  let contentType = '';
  let multipart = '';
  await page.route('**/api/v1/kb/kb_fixture/documents', async (route) => {
    contentType = route.request().headers()['content-type'];
    multipart = route.request().postDataBuffer()?.toString() ?? '';
    await route.fulfill({ json: { code: 'OK', data: { ...documents[0], duplicated: true, version: 'V1' } } });
  });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('button', { name: '添加文档' }).click();
  const drawer = page.getByRole('dialog', { name: '添加文档' });
  await drawer
    .locator('input[type=file]')
    .setInputFiles({
      name: 'manual.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('upload-contract-fixture'),
    });
  await expect(page.getByText('manual.txt 内容与已有版本 V1一致，未重复建版')).toBeVisible();
  expect(contentType).toContain('multipart/form-data; boundary=');
  expect(multipart).toContain('name="file"; filename="manual.txt"');
  expect(multipart).toContain('upload-contract-fixture');
  expect(unexpectedRequests).toEqual([]);
});

test('知识库设置保存失败保留输入，重试成功更新标题并能进入索引配置', async ({
  page,
  api,
  unexpectedRequests,
}) => {
  let attempts = 0;
  const payloads: unknown[] = [];
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/v1/kb/kb_fixture', async (route) => {
    if (route.request().method() === 'GET') return route.fallback();
    attempts += 1;
    payloads.push(route.request().postDataJSON());
    if (attempts === 1)
      return route.fulfill({
        status: 503,
        json: { code: 'TEMPORARY_FAILURE', message: '暂时无法保存，请重试' },
      });
    const saved = { ...kb, name: '交付知识库', description: '更新后的服务说明' };
    api['/kb/kb_fixture'] = saved;
    await route.fulfill({ json: { code: 'OK', data: saved } });
  });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('button', { name: '知识库设置' }).click();
  const drawer = page.getByRole('dialog', { name: '知识库设置' });
  await drawer.getByLabel('知识库名称').fill('交付知识库');
  await drawer.getByLabel('描述', { exact: true }).fill('更新后的服务说明');
  await drawer.getByRole('button', { name: '保存基本信息' }).click();
  await expect(page.getByText('暂时无法保存，请重试')).toBeVisible();
  await expect(drawer.getByLabel('知识库名称')).toHaveValue('交付知识库');
  await drawer.getByRole('button', { name: '保存基本信息' }).click();
  await expect(drawer).toBeHidden();
  await expect(page.getByRole('heading', { name: '交付知识库', exact: true })).toBeVisible();
  expect(payloads).toEqual(Array(2).fill({ name: '交付知识库', description: '更新后的服务说明' }));
  await page.getByRole('button', { name: '知识库设置' }).click();
  await drawer.getByRole('button', { name: '编辑索引配置' }).click();
  await expect(page.getByRole('dialog', { name: '索引配置', exact: true })).toBeVisible();
  expect(errors).toEqual([]);
  expect(unexpectedRequests).toEqual([]);
});

test('批量回收仅发送勾选的文档，反馈服务端实际处理数量', async ({ page, api, unexpectedRequests }) => {
  let payload: unknown;
  await page.route('**/api/v1/kb/kb_fixture/documents/batch-delete', async (route) => {
    payload = route.request().postDataJSON();
    api['/kb/kb_fixture/documents'] = pageData(
      documents.filter((doc) => !['doc_0', 'doc_1'].includes(doc.doc_id)),
    );
    await route.fulfill({ json: { code: 'OK', data: { deleted_doc_ids: ['doc_0'] } } });
  });
  await page.goto('/kb/kb_fixture');
  await page.getByRole('checkbox', { name: '选择 产品使用手册.pdf' }).check();
  await page.getByRole('checkbox', { name: '选择 售后服务政策.docx' }).check();
  await page.getByRole('button', { name: /批量删除|批量移入回收站/ }).click();
  expect(payload).toBeUndefined();
  await page.getByRole('button', { name: '移入回收站', exact: true }).click();
  await expect.poll(() => payload).toEqual({ doc_ids: ['doc_0', 'doc_1'] });
  await expect(page.getByText('已将 1 个文档移入回收站，1 个已在回收站中，已跳过')).toBeVisible();
  await expect(page.getByRole('alert').filter({ hasText: '已选中' })).toHaveCount(0);
  expect(unexpectedRequests).toEqual([]);
});

test('审核先查看再确认发布，刷新为服务端返回的发布状态', async ({ page, api, unexpectedRequests }) => {
  let approvals = 0;
  await page.route('**/api/v1/documents/doc_1/approve', async (route) => {
    approvals += 1;
    const published = { ...documents[1], publish_status: 'PUBLISHED' };
    api['/kb/kb_fixture/documents'] = pageData(
      documents.map((doc) => (doc.doc_id === 'doc_1' ? published : doc)),
    );
    await route.fulfill({ json: { code: 'OK', data: published } });
  });
  await page.goto('/kb/kb_fixture');
  await page
    .locator('tr[data-row-key="doc_1"]')
    .getByRole('button', { name: /^审\s*核$/ })
    .click();
  const drawer = page.getByRole('dialog', { name: '审核文档' });
  await expect(drawer).toContainText('售后服务政策.docx');
  await drawer.getByRole('button', { name: '通过并发布' }).click();
  expect(approvals).toBe(0);
  await page.locator('.ant-popconfirm').getByRole('button', { name: '通过并发布' }).click();
  await expect.poll(() => approvals).toBe(1);
  await expect(drawer).toBeHidden();
  await expect(page.locator('tr[data-row-key="doc_1"]')).toContainText('已发布');
  expect(unexpectedRequests).toEqual([]);
});

// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  BatchDeleteDocumentsResult,
  BatchReindexDocumentsResult,
  ConfirmDocumentsRequest,
  CreateKbRequest,
  DocumentBatchRequest,
  KnowledgeBase,
  RebuildRequest,
  RebuildStatus,
  UpdateIndexConfigRequest,
  UpdateKbRequest,
} from './types';

export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return apiGet<KnowledgeBase[]>('/kb');
}

export function getKnowledgeBase(kbId: string): Promise<KnowledgeBase> {
  return apiGet<KnowledgeBase>(`/kb/${kbId}`);
}

export function createKnowledgeBase(payload: CreateKbRequest): Promise<KnowledgeBase> {
  return apiPost<KnowledgeBase>('/kb', payload);
}

/** PUT /api/v1/kb/{kbId}: renames the base and updates its description, nothing else. */
export function updateKnowledgeBase(kbId: string, payload: UpdateKbRequest): Promise<KnowledgeBase> {
  return apiPut<KnowledgeBase>(`/kb/${kbId}`, payload);
}

export function deleteKnowledgeBase(kbId: string): Promise<void> {
  return apiDelete<void>(`/kb/${kbId}`);
}

/** Response of PUT /kb/{kbId}/index-config: what the save actually invalidated. */
export interface UpdateIndexConfigResult {
  /** Documents now flagged config_stale against the new fingerprint, i.e. awaiting a rebuild. */
  stale_documents: number;
  /** The recomputed current_config_fingerprint. */
  fingerprint: string;
}

/**
 * PUT /api/v1/kb/{kbId}/index-config (M2-CONTRACTS.md section 4). The server answers with the
 * stale-document count it just recomputed, which is the only place that number is available
 * without re-listing and re-counting the documents client-side.
 */
export function updateIndexConfig(
  kbId: string,
  payload: UpdateIndexConfigRequest,
): Promise<UpdateIndexConfigResult> {
  return apiPut<UpdateIndexConfigResult>(`/kb/${kbId}/index-config`, payload);
}

/**
 * POST /api/v1/kb/{kbId}/rebuild (M2-CONTRACTS.md section 4). Body omitted/empty means
 * "all config_stale documents" —— 配置追平就该按整库来，传当前页的 doc_ids 会漏掉翻页外的
 * 待重建文档。进度用 {@link getRebuildStatus} 轮询，不要在调用方记账。
 */
export function rebuildKb(kbId: string, payload?: RebuildRequest): Promise<void> {
  return apiPost<void>(`/kb/${kbId}/rebuild`, payload);
}

/**
 * GET /api/v1/kb/{kbId}/rebuild-status：整库的配置追平状态。
 *
 * 重建跑在服务端线程池里，比发起它的那个页面活得久：调用方离开详情页再回来、刷新、换个人打开，
 * 都必须能看到同一份进度，所以状态只能问服务端，不能存在组件里。
 */
export function getRebuildStatus(kbId: string): Promise<RebuildStatus> {
  return apiGet<RebuildStatus>(`/kb/${kbId}/rebuild-status`);
}

/**
 * POST /api/v1/kb/{kbId}/documents/confirm (M3-CONTRACTS.md section 3.4): batch-confirms
 * PENDING_CONFIRM documents past the parse-preview pause. Omitted doc_ids = every PENDING_CONFIRM
 * document in this KB.
 */
export function confirmKbDocuments(kbId: string, payload?: ConfirmDocumentsRequest): Promise<void> {
  return apiPost<void>(`/kb/${kbId}/documents/confirm`, payload);
}

/**
 * POST /api/v1/kb/{kbId}/documents/batch-delete：把勾选的文档整批移入回收站。
 *
 * 与循环调用单篇 DELETE 的差别在服务端：作用域一次校验、审计一条记录；勾选中途被别人删掉的文档
 * 会被跳过而不是让整批失败，所以返回的是真正删掉的那些，调用方据此提示而不是照搬勾选数。
 */
export function batchDeleteDocuments(kbId: string, docIds: string[]): Promise<BatchDeleteDocumentsResult> {
  const payload: DocumentBatchRequest = { doc_ids: docIds };
  return apiPost<BatchDeleteDocumentsResult>(`/kb/${kbId}/documents/batch-delete`, payload);
}

/**
 * POST /api/v1/kb/{kbId}/documents/batch-reindex：把勾选的文档整批重跑解析与索引。
 *
 * 注意与 {@link rebuildKb} 的区别：那个按当前索引配置重建、用于配置变更后的追平；这个等同表格里
 * 每行的「重建」按钮，完整重跑一遍 pipeline。
 */
export function batchReindexDocuments(kbId: string, docIds: string[]): Promise<BatchReindexDocumentsResult> {
  const payload: DocumentBatchRequest = { doc_ids: docIds };
  return apiPost<BatchReindexDocumentsResult>(`/kb/${kbId}/documents/batch-reindex`, payload);
}

/**
 * PUT /api/v1/kb/{kbId}/governance (M11-CONTRACTS.md section 2.2): flips the review switch. Only
 * future uploads read it -- documents already present keep their publish_status.
 */
export function updateKbGovernance(kbId: string, reviewRequired: boolean): Promise<KnowledgeBase> {
  return apiPut<KnowledgeBase>(`/kb/${kbId}/governance`, { review_required: reviewRequired });
}

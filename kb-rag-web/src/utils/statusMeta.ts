// Author: owlzhangfq@gmail.com
import type {
  AnchorType,
  AnnotationType,
  ApiKeyStatus,
  AppVersionStatus,
  CaseSource,
  CaseStatus,
  ChatImportAction,
  ChunkType,
  DocumentVersionStatus,
  EmbeddingStatus,
  EvalMode,
  ExtSourceItemStatus,
  ExtSourceSyncStatus,
  FeedbackStatus,
  FeedbackVerdict,
  FusionMode,
  GraphTaskStatus,
  IkDictStatus,
  IkDictType,
  InheritStatus,
  MetricGroupKey,
  ProcessStatus,
  RetrievalSource,
  RollbackMode,
  RunStatus,
  ScoreType,
  SearchInsightSource,
  SourceMappingType,
  SplitStrategy,
  ThresholdAppliedOn,
  WebSourceFetchStatus,
} from '../api/types';

/** Ant Design Tag color + Chinese label per process_status, single source of truth for the UI. */
export const PROCESS_STATUS_META: Record<ProcessStatus, { color: string; label: string }> = {
  UPLOADED: { color: 'default', label: '已上传' },
  PARSING: { color: 'processing', label: '解析中' },
  PARSE_FAILED: { color: 'error', label: '解析失败' },
  PARSED: { color: 'processing', label: '已解析' },
  // M3-CONTRACTS.md section 3.4: pipeline paused after clean, waiting on preview confirm/reparse.
  PENDING_CONFIRM: { color: 'gold', label: '待确认' },
  INDEXING: { color: 'processing', label: '索引中' },
  INDEXED: { color: 'success', label: '已就绪' },
  INDEX_FAILED: { color: 'error', label: '索引失败' },
};

/** chunk_type Tag meta (M3-CONTRACTS.md section 4: retrieval card image/chat_log tags). */
export const CHUNK_TYPE_META: Record<ChunkType, TagMeta> = {
  text: { color: 'default', label: '文本' },
  image: { color: 'purple', label: '图片' },
  chat_log: { color: 'cyan', label: '聊天记录' },
};

/** chat-import session action Tag meta (M3-CONTRACTS.md section 3.5/4). */
export const CHAT_IMPORT_ACTION_META: Record<ChatImportAction, TagMeta> = {
  CREATE: { color: 'success', label: '新建文档' },
  NEW_VERSION: { color: 'processing', label: '生成新版本' },
};

/** t_kb_source_mapping.source_type Tag meta (M8-CONTRACTS.md section 0.7), shared by the "导入映射" tab and the chat-import mapping picker. */
export const SOURCE_MAPPING_TYPE_META: Record<SourceMappingType, TagMeta> = {
  csv: { color: 'blue', label: 'CSV' },
  xlsx: { color: 'green', label: 'XLSX' },
  txt: { color: 'gold', label: 'TXT' },
  html: { color: 'purple', label: 'HTML' },
};

export const EMBEDDING_STATUS_META: Record<EmbeddingStatus, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '待嵌入' },
  DONE: { color: 'success', label: '已嵌入' },
  FAILED: { color: 'error', label: '嵌入失败' },
  SKIPPED: { color: 'warning', label: '跳过(零Key)' },
};

/** score_type enum, extended by M2-CONTRACTS.md section 1.3 (rerank/fused_rrf/fused_weighted). */
export const SCORE_TYPE_META: Record<ScoreType, { color: string; label: string }> = {
  rerank: { color: 'volcano', label: 'rerank' },
  cosine: { color: 'blue', label: 'cosine' },
  bm25_rank: { color: 'purple', label: 'bm25_rank' },
  fused_rrf: { color: 'cyan', label: 'fused_rrf' },
  fused_weighted: { color: 'geekblue', label: 'fused_weighted' },
};

export const RETRIEVAL_SOURCE_META: Record<RetrievalSource, { color: string; label: string }> = {
  vector: { color: 'geekblue', label: 'vector' },
  bm25: { color: 'gold', label: 'bm25' },
};

export const FUSION_MODE_META: Record<FusionMode, { color: string; label: string }> = {
  rrf: { color: 'processing', label: 'RRF（倒数排名融合）' },
  weighted: { color: 'processing', label: '加权归一化融合' },
};

/**
 * M7-CONTRACTS.md section 0.6/§4.4: shared copy for every fusion_mode picker (SearchPage debug
 * form, AppConfigTab) that must grey out "加权归一化融合" once the retrieval targets a
 * graph_enabled knowledge base -- the server enforces the same rule as an INVALID_PARAM on save.
 */
export const GRAPH_FUSION_MUTEX_HINT = '开启图路的知识库库内融合强制为 RRF';

/**
 * index_config.split_strategy picker meta (M14 contract section 4). Five strategies exist
 * server-side; parent/child chunking is NOT one of them -- it is its own switch. That switch is not
 * orthogonal though: the two level splitter composes 定长切分 with itself rather than taking the
 * configured strategy, so turning it on confines the picker to fixed_length and the server rejects
 * every other combination as INVALID_PARAM.
 */
export const SPLIT_STRATEGY_META: Record<SplitStrategy, TagMeta> = {
  fixed_length: { color: 'default', label: '定长切分' },
  llm_semantic: { color: 'purple', label: 'LLM 语义切分' },
  separator: { color: 'blue', label: '分隔符切分' },
  heading: { color: 'geekblue', label: '标题层级切分' },
  page: { color: 'cyan', label: '按页切分' },
};

/** Shown wherever 「LLM 语义切分」 has to be greyed out for want of a chat model. */
export const LLM_SPLIT_REQUIRES_CHAT_MODEL = 'LLM 语义切分需要已配置对话模型，请先在系统设置中配置';

/** Shown wherever the picker has to be confined to 定长切分 because 父子分片 is on. */
export const PARENT_CHILD_REQUIRES_FIXED_LENGTH =
  '父子分片当前仅支持定长切分：父片与子片均由定长策略切出，请先关闭父子分片再选择其他切分方式';

export const IK_DICT_TYPE_META: Record<IkDictType, { color: string; label: string }> = {
  EXT: { color: 'success', label: '扩展词' },
  STOP: { color: 'default', label: '停用词' },
};

export const IK_DICT_STATUS_META: Record<IkDictStatus, { color: string; label: string }> = {
  ENABLED: { color: 'success', label: '已启用' },
  DISABLED: { color: 'default', label: '已停用' },
};

/**
 * t_kb_document_version.status Tag meta (M4a-CONTRACTS.md section 0/1.2). ACTIVE is the one
 * currently serving retrieval; READY versions are kept for instant rollback within the retention
 * window; ARCHIVED versions had their chunk rows swept and need a REBUILD task to reactivate.
 */
export const DOCUMENT_VERSION_STATUS_META: Record<DocumentVersionStatus, TagMeta> = {
  BUILDING: { color: 'processing', label: '构建中' },
  BUILD_FAILED: { color: 'error', label: '构建失败' },
  READY: { color: 'default', label: '就绪（未激活）' },
  ACTIVE: { color: 'success', label: '当前激活' },
  ARCHIVED: { color: 'default', label: '已归档' },
};

/** Activation rollback_mode Tag meta (M4a-CONTRACTS.md section 1.2). */
export const ROLLBACK_MODE_META: Record<RollbackMode, TagMeta> = {
  INSTANT: { color: 'success', label: '即时切换' },
  REBUILD: { color: 'warning', label: '需重建' },
};

/** t_kb_annotation.annotation_type Tag meta (M4a-CONTRACTS.md section 2.4). */
export const ANNOTATION_TYPE_META: Record<AnnotationType, TagMeta> = {
  EDIT: { color: 'blue', label: '编辑' },
  TOGGLE: { color: 'gold', label: '启/禁用' },
  MERGE: { color: 'purple', label: '合并' },
  SPLIT: { color: 'cyan', label: '拆分' },
};

/** t_kb_annotation.inherit_status Tag meta (M4a-CONTRACTS.md sections 2.3/2.4). */
export const INHERIT_STATUS_META: Record<InheritStatus, TagMeta> = {
  NOT_INHERITED: { color: 'warning', label: '未继承' },
  AUTO_INHERITED: { color: 'success', label: '自动继承' },
  REDONE: { color: 'processing', label: '已在新版本重做' },
};

/** t_kb_eval_case.anchor_type Tag meta (M4b-CONTRACTS.md sections 1/5). */
export const ANCHOR_TYPE_META: Record<AnchorType, TagMeta> = {
  SPAN: { color: 'blue', label: '片段锚定' },
  DOCUMENT: { color: 'purple', label: '文档锚定' },
};

/**
 * t_kb_eval_case.status Tag meta (M4b-CONTRACTS.md sections 1/5). Unrelated to M4a's
 * InheritStatus despite the similarly-shaped values -- do not cross-index the two tables.
 */
export const CASE_STATUS_META: Record<CaseStatus, TagMeta> = {
  ACTIVE: { color: 'success', label: '生效' },
  EVIDENCE_STALE: { color: 'warning', label: '待复核' },
  DEPRECATED: { color: 'default', label: '已废弃' },
};

/** t_kb_eval_case.source Tag meta (M4b-CONTRACTS.md section 1; FEEDBACK added by M10-CONTRACTS.md section 2.1). */
export const CASE_SOURCE_META: Record<CaseSource, TagMeta> = {
  MANUAL: { color: 'default', label: '手动标注' },
  DEBUG_PAGE: { color: 'processing', label: '检索调试收录' },
  IMPORTED: { color: 'cyan', label: '导入' },
  FEEDBACK: { color: 'gold', label: '反馈转化' },
};

/** t_kb_retrieval_feedback.verdict Tag meta (M10-CONTRACTS.md section 2.1). */
export const FEEDBACK_VERDICT_META: Record<FeedbackVerdict, TagMeta> = {
  GOOD: { color: 'success', label: '好结果' },
  BAD: { color: 'error', label: '坏结果' },
};

/**
 * t_kb_retrieval_feedback.status Tag meta (M10-CONTRACTS.md section 2.1). CONVERTED and
 * DISMISSED are both terminal server-side; the feedback tab keys its row actions off NEW only.
 */
export const FEEDBACK_STATUS_META: Record<FeedbackStatus, TagMeta> = {
  NEW: { color: 'processing', label: '待处理' },
  CONVERTED: { color: 'success', label: '已转评测集' },
  DISMISSED: { color: 'default', label: '已忽略' },
};

/** t_kb_search_insight.source Tag meta (M10-CONTRACTS.md section 2.2). */
export const SEARCH_INSIGHT_SOURCE_META: Record<SearchInsightSource, TagMeta> = {
  CONSOLE: { color: 'processing', label: '控制台调试' },
  OPEN_API: { color: 'purple', label: 'OpenAPI' },
};

/**
 * t_kb_web_source.last_fetch_status Tag meta (M12-CONTRACTS.md section 3.4). UNCHANGED/SKIPPED
 * are deliberate non-writes, not failures, hence the neutral colors.
 */
export const WEB_SOURCE_STATUS_META: Record<WebSourceFetchStatus, TagMeta> = {
  SUCCESS: { color: 'success', label: '已入库' },
  UNCHANGED: { color: 'default', label: '内容未变' },
  SKIPPED: { color: 'warning', label: '已跳过' },
  FAILED: { color: 'error', label: '失败' },
};

/**
 * ExtSource.last_sync_status Tag meta (M14 contract section 2.3): outcome of the last whole-source
 * sync pass. PARTIAL means the scan ran but some objects failed/were skipped (see the item rows).
 */
export const EXT_SOURCE_SYNC_STATUS_META: Record<ExtSourceSyncStatus, TagMeta> = {
  SUCCESS: { color: 'success', label: '同步成功' },
  PARTIAL: { color: 'warning', label: '部分成功' },
  FAILED: { color: 'error', label: '同步失败' },
};

/**
 * ExtSourceItem.last_status Tag meta (M14 contract section 2.3): per-object outcome. UNCHANGED
 * (etag identical) and SKIPPED (bound document in the recycle bin) are deliberate non-writes.
 */
export const EXT_SOURCE_ITEM_STATUS_META: Record<ExtSourceItemStatus, TagMeta> = {
  SUCCESS: { color: 'success', label: '已入库' },
  UNCHANGED: { color: 'default', label: '内容未变' },
  SKIPPED: { color: 'warning', label: '已跳过' },
  FAILED: { color: 'error', label: '失败' },
};

/** t_kb_eval_run.status Tag meta (M4b-CONTRACTS.md sections 1/3.1). */
export const RUN_STATUS_META: Record<RunStatus, TagMeta> = {
  PENDING: { color: 'default', label: '待执行' },
  RUNNING: { color: 'processing', label: '执行中' },
  SUCCESS: { color: 'success', label: '成功' },
  FAILED: { color: 'error', label: '失败' },
};

/** Run config matrix mode Tag meta (M4b-CONTRACTS.md section 3.1). */
export const EVAL_MODE_META: Record<EvalMode, TagMeta> = {
  BM25_ONLY: { color: 'gold', label: 'BM25 单路' },
  VECTOR_ONLY: { color: 'geekblue', label: '向量单路' },
  HYBRID: { color: 'processing', label: '混合检索' },
  HYBRID_RERANK: { color: 'volcano', label: '混合+重排' },
};

/**
 * Report metric grouping Tag meta (M4b-CONTRACTS.md section 3.3: "全体/span级/文档级/单轮/多轮").
 * Not one of the four backend enums the M4b contract names explicitly, but routed through the
 * same metaOf defense since the group key still originates from a run's server-computed metrics
 * JSON.
 */
export const METRIC_GROUP_META: Record<MetricGroupKey, TagMeta> = {
  all: { color: 'default', label: '全体' },
  span: { color: 'blue', label: 'span 级' },
  document: { color: 'purple', label: '文档级' },
  single_turn: { color: 'default', label: '单轮' },
  multi_turn: { color: 'default', label: '多轮' },
};

/**
 * t_kb_app_version.status Tag meta (M4c-CONTRACTS.md sections 1/2/4): the release/gate state
 * machine's eight states, hard-constrained by the M4c web scope to always render through this
 * table (never a raw switch), including the "仅记录不拦截" GATE_LOG_ONLY state that still requires
 * an explicit force-release action rather than blocking release outright.
 */
export const APP_VERSION_STATUS_META: Record<AppVersionStatus, TagMeta> = {
  DRAFT: { color: 'default', label: '草稿' },
  TESTING: { color: 'processing', label: '测试中' },
  GATING: { color: 'processing', label: '门禁执行中' },
  GATE_PASSED: { color: 'cyan', label: '门禁通过' },
  GATE_LOG_ONLY: { color: 'warning', label: '门禁仅记录' },
  GATE_BLOCKED: { color: 'error', label: '门禁拦截' },
  RELEASED: { color: 'success', label: '已发布' },
  SUPERSEDED: { color: 'default', label: '已下线' },
};

/** App version statuses that mean "gate is still running", used to drive 3s polling. */
export const APP_VERSION_GATING_STATUSES: AppVersionStatus[] = ['GATING'];

/**
 * t_kb_api_key.status Tag meta (M4c-CONTRACTS.md sections 1/3). See ApiKeyStatus's doc comment in
 * api/types.ts for why the two values are an assumption rather than a literal contract quote.
 */
export const API_KEY_STATUS_META: Record<ApiKeyStatus, TagMeta> = {
  ENABLED: { color: 'success', label: '启用' },
  DISABLED: { color: 'default', label: '已禁用' },
};

/**
 * GraphTaskStatus Tag meta (M7-CONTRACTS.md section 0.10, GraphSummary.latest_task.status).
 * Reuses the same four-state vocabulary as RUN_STATUS_META (see GraphTaskStatus's doc comment in
 * api/types.ts) but kept as its own table since the two enums are unrelated server concepts.
 */
export const GRAPH_TASK_STATUS_META: Record<GraphTaskStatus, TagMeta> = {
  PENDING: { color: 'default', label: '待执行' },
  RUNNING: { color: 'processing', label: '抽取中' },
  SUCCESS: { color: 'success', label: '已完成' },
  FAILED: { color: 'error', label: '失败' },
};

/** Tag metadata shape shared by every enum lookup table in this module. */
export interface TagMeta {
  color: string;
  label: string;
}

/**
 * Single defensive lookup for values that come from the server.
 *
 * <p>Indexing a lookup table directly crashes the whole page when the backend introduces an enum
 * value the console does not know yet, which is the one failure mode a display helper must never
 * have. Every call site goes through here so the fallback lives in exactly one place, and an
 * unmapped value degrades to showing the raw code instead of blanking the view.
 */
export function metaOf(table: Record<string, TagMeta>, key: string | null | undefined): TagMeta {
  if (!key) {
    return { color: 'default', label: '未知' };
  }
  return table[key] ?? { color: 'default', label: key };
}

/** Known degraded reason codes mapped to a human readable Chinese explanation. */
export const DEGRADED_REASON_LABELS: Record<string, string> = {
  query_rewrite_error: '查询改写失败，已使用原始查询检索',
  query_rewrite_unavailable: '未配置对话模型，查询改写未启用',
  rerank_unavailable: '未配置重排模型，重排序未启用',
  vector_route_unavailable: '向量检索不可用，已降级为 BM25 单路检索',
  query_rewrite_timeout: '查询改写超时或失败，已使用原始查询检索',
  rerank_timeout: '重排序超时，已使用融合排序结果',
  rerank_error: '重排序服务异常，已使用融合排序结果',
  threshold_inactive: 'BM25 单路检索下阈值过滤未生效',
  /** M5-CONTRACTS.md section 2.1: router output empty/unparseable/timed out -> fell back to searching every kb_refs member. */
  route_fallback_all: '知识库路由降级，已检索该应用关联的全部知识库',
  /**
   * M6-CONTRACTS.md section 0.5: a RELEASED-version call found its snapshot physical index
   * missing (e.g. accidentally deleted) and fell back to the live alias + current active set.
   * Distinct from the "旧 RELEASED（M6 前发布，无快照数据）" branch in section 0.4, which is the
   * same fallback but explicitly NOT recorded as degraded -- only an actually-missing snapshot
   * (one that was expected to exist) counts as this reason.
   */
  snapshot_index_missing: '快照索引缺失，已回退实时索引',
  /**
   * M7-CONTRACTS.md section 0.7: the kb has graph_enabled=true but Neo4j is unreachable/unconfigured
   * at call time -- that route is skipped, the other two (vector/BM25) proceed unaffected.
   */
  graph_route_unavailable: '图检索路不可用，已降级双路',
  /**
   * M9-CONTRACTS.md section 0.6: images param present but zero-Key/no-vision-model/timeout/failed
   * for every image -- all images are dropped and retrieval falls back to text-only on `query`.
   */
  image_understanding_unavailable: '图片理解不可用，已忽略图片仅文本检索',
  /**
   * M14 contract section 6.3: the kb has multimodal_enabled=true but fusion_mode is `weighted`;
   * RRF is the only mode that can merge a route without a comparable absolute score, so the
   * multimodal route is skipped rather than folded in on an incompatible scale.
   */
  mm_route_skipped: '多模态路检索在加权融合下不生效，已跳过该路',
  /**
   * M14 contract section 6.3: the kb has multimodal_enabled=true but embedding the query into the
   * multimodal space failed/timed out -- that route is skipped, the other routes proceed.
   */
  mm_route_unavailable: '多模态检索不可用，已降级为其余路检索',
};

export function describeDegradedReason(reason: string): string {
  return DEGRADED_REASON_LABELS[reason] ?? reason;
}

/**
 * Resolves the "threshold applied on" tag shown both in the applied info bar and on each
 * result card (M2-CONTRACTS.md section 5). Returns null when no threshold was configured at all.
 */
export function describeThresholdApplied(
  thresholdAppliedOn: ThresholdAppliedOn | null,
  degraded: string[],
): TagMeta | null {
  if (degraded.includes('threshold_inactive')) {
    return { color: 'warning', label: '阈值未生效（BM25 单路）' };
  }
  // The server sends 'none' when nothing was filtered, which is not a score type.
  if (!thresholdAppliedOn || thresholdAppliedOn === 'none') {
    return null;
  }
  return { color: 'processing', label: `阈值作用于 ${metaOf(SCORE_TYPE_META, thresholdAppliedOn).label}` };
}

// Shared type definitions mirroring kb-rag-deploy/docs/M1-CONTRACTS.md section 5 and
// kb-rag-deploy/docs/M2-CONTRACTS.md sections 1/3/4 (REST API contract).
// Field names intentionally keep the backend's snake_case JSON casing so the payloads
// can be typed as-is without a mapping layer.

/** Standard response envelope returned by every kb-rag-server endpoint. */
export interface ApiResult<T> {
  code: string;
  message: string;
  data: T;
  request_id: string;
}

/** Page wrapper returned by kb-rag-server listing endpoints (PageResponse record). */
export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  must_change_password: boolean;
}

export interface ChangePasswordRequest {
  old_password: string;
  new_password: string;
}

export interface CurrentUser {
  username: string;
  must_change_password: boolean;
  last_login_at: string | null;
}

// ---------------------------------------------------------------------------
// Knowledge base
// ---------------------------------------------------------------------------

/** M2-CONTRACTS.md section 1.4: parent/child two-level chunking switch and lengths. */
export interface ParentChildConfig {
  enabled: boolean;
  parent_max_tokens: number;
  child_max_tokens: number;
  child_overlap: number;
}

/** One entry of index_config.clean_rules.regex_replacements (M3-CONTRACTS.md section 3.3). */
export interface RegexReplacement {
  pattern: string;
  replacement: string;
}

/**
 * index_config.clean_rules.desensitize (M3-CONTRACTS.md section 3.3): four independent regex
 * mask switches under one master `enabled` flag. Chat log imports default this to enabled.
 */
export interface DesensitizeConfig {
  enabled: boolean;
  phone: boolean;
  id_card: boolean;
  bank_card: boolean;
  email: boolean;
}

/**
 * t_kb_knowledge_base.index_config.clean_rules (M3-CONTRACTS.md section 3.3). Execution order is
 * fixed and not user-configurable: strip_header_footer -> strip_watermark_patterns (regex) ->
 * regex_replacements -> desensitize.
 */
export interface CleanRules {
  strip_header_footer: boolean;
  strip_watermark_patterns: string[];
  regex_replacements: RegexReplacement[];
  excel_header_join: boolean;
  /**
   * Present in the clean_rules schema but not part of the M3 web deliverable's clean-rules
   * form group (M3-CONTRACTS.md section 4 lists 页眉页脚/水印/正则替换/Excel表头/脱敏 only), so
   * the drawer carries this value through unmodified (hidden field) instead of rendering a control.
   */
  extract_metadata: boolean;
  desensitize: DesensitizeConfig;
}

/**
 * index_config.chat_aggregation (M3-CONTRACTS.md section 3.5): no-overlap sequential windowing
 * applied when slicing an imported chat session into chunk_type=chat_log chunks.
 * ASSUMPTION: the contract calls this "KB 级配置" without spelling out its exact JSON path;
 * nested under index_config alongside clean_rules/parse_preview_required since section 4 groups
 * all three ("清洗规则分组...解析预览开关、聊天聚合参数") under the same index-config drawer/API.
 */
export interface ChatAggregationConfig {
  window_minutes: number;
  max_messages: number;
}

/**
 * t_kb_knowledge_base.index_config JSON (M1-CONTRACTS.md section 2).
 * ASSUMPTION: exact field names for the flat chunking params are not spelled out by the
 * contract beyond "分段长度/重叠" (M1 defaults: 600 token length / 100 overlap) and the
 * parent_child block (M2-CONTRACTS.md section 1.4); named here as chunk_max_tokens/chunk_overlap
 * to mirror the TokenEstimator-based pipeline description in M1-CONTRACTS.md section 4.
 */
export interface IndexConfig {
  chunk_max_tokens: number;
  chunk_overlap: number;
  parent_child: ParentChildConfig;
  /** M3-CONTRACTS.md section 3.3. */
  clean_rules: CleanRules;
  /** M3-CONTRACTS.md section 3.4; KB-level switch, default false. */
  parse_preview_required: boolean;
  /** M3-CONTRACTS.md section 3.5; see ChatAggregationConfig doc comment for the nesting assumption. */
  chat_aggregation: ChatAggregationConfig;
  /**
   * M4a-CONTRACTS.md section 2.2/2.4: when a parent chunk contains any disabled child chunk,
   * hide the parent from search results entirely instead of returning it with
   * metadata.disabled_child_ids. Default false. Does not participate in parse/chunk fingerprints.
   */
  hide_parent_with_disabled_child: boolean;
  /**
   * M4a-CONTRACTS.md section 2.3/2.4: auto-carry a TOGGLE(disable) annotation over to a new
   * document version when the target chunk's chunk_text_hash matches exactly (no similarity
   * matching). Default true. Does not participate in parse/chunk fingerprints.
   */
  inherit_disable_annotation: boolean;
}

/** PUT /api/v1/kb/{kbId}/index-config request body (M2-CONTRACTS.md section 4). */
export type UpdateIndexConfigRequest = IndexConfig;

export interface KnowledgeBase {
  kb_id: string;
  name: string;
  description: string | null;
  /**
   * ASSUMPTION: GET /api/v1/kb and GET /api/v1/kb/{kbId} return the current index_config
   * alongside the fingerprint so the M2 edit drawer can be pre-filled without a separate
   * fetch; not explicitly listed in M1/M2-CONTRACTS.md's response shape.
   */
  index_config: IndexConfig | null;
  current_config_fingerprint: string | null;
  created_at: string;
  updated_at: string;
}

export interface CreateKbRequest {
  name: string;
  description?: string;
}

// ---------------------------------------------------------------------------
// Document / chunk
// ---------------------------------------------------------------------------

/**
 * t_kb_document.process_status, see M1-CONTRACTS.md section 2, extended by M3-CONTRACTS.md
 * section 3.4: PENDING_CONFIRM is entered when the KB has parse_preview_required=true and the
 * pipeline pauses after clean/before chunking, waiting for a human confirm or reparse.
 */
export type ProcessStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'PARSE_FAILED'
  | 'PARSED'
  | 'PENDING_CONFIRM'
  | 'INDEXING'
  | 'INDEXED'
  | 'INDEX_FAILED';

/** Process statuses that mean "the pipeline is still working", used to drive 3s polling. */
export const IN_PROGRESS_STATUSES: ProcessStatus[] = ['UPLOADED', 'PARSING', 'PARSED', 'INDEXING'];

/** Process statuses that mean "the pipeline failed and fail_reason should be surfaced". */
export const FAILED_STATUSES: ProcessStatus[] = ['PARSE_FAILED', 'INDEX_FAILED'];

export interface KbDocument {
  doc_id: string;
  kb_id: string;
  file_name: string;
  file_ext: string;
  file_size: number;
  current_version_id: string | null;
  process_status: ProcessStatus;
  config_stale: boolean;
  fail_reason: string | null;
  created_at: string;
  updated_at: string;
}

export type EmbeddingStatus = 'PENDING' | 'DONE' | 'FAILED' | 'SKIPPED';

export interface KbChunk {
  chunk_id: string;
  kb_id: string;
  doc_id: string;
  document_version_id: string;
  content: string;
  parent_id: string | null;
  seq: number;
  enabled: boolean;
  embedding_status: EmbeddingStatus;
  metadata: Record<string, unknown> | null;
}

/** POST /api/v1/kb/{kbId}/rebuild request body (M2-CONTRACTS.md section 4). */
export interface RebuildRequest {
  doc_ids?: string[];
}

// ---------------------------------------------------------------------------
// Retrieval
// ---------------------------------------------------------------------------

/**
 * chunk_type, see M1-CONTRACTS.md section 6 (ES field `chunk_type(keyword: text|image|chat_log)`)
 * and M3-CONTRACTS.md sections 3.2/3.5: image = standalone uploaded picture, chat_log = one
 * aggregation window of an imported chat session, text = everything else (embedded-image proxy
 * text is folded into a normal text chunk, not its own chunk_type).
 */
export type ChunkType = 'text' | 'image' | 'chat_log';

/** score_type enum, extended by M2-CONTRACTS.md section 1.3. */
export type ScoreType = 'rerank' | 'cosine' | 'bm25_rank' | 'fused_rrf' | 'fused_weighted';
export type RetrievalSource = 'vector' | 'bm25';

export type FusionMode = 'rrf' | 'weighted';

/** M2-CONTRACTS.md section 1.5 fusion sub-object of the search request. */
export interface FusionConfig {
  mode: FusionMode;
  /** Only meaningful when mode = 'weighted'; weight of the vector route, 0-1 (w_bm25 = 1 - w_vec). */
  w_vec?: number;
  /** Only meaningful when mode = 'rrf'; default 60. */
  rrf_k?: number;
}

/** M2-CONTRACTS.md section 1.5 metadata_filter sub-object of the search request. */
export interface MetadataFilter {
  tag_ids?: string[];
  session_id?: string;
  sender?: string;
  /** epoch millis, inclusive lower bound (chunk.metadata.msg_time is bigint millis per requirements doc section "结构过滤"). */
  msg_time_from?: number;
  /** epoch millis, inclusive upper bound. */
  msg_time_to?: number;
}

/**
 * Chat turn used for multi-turn coreference-aware query rewriting (M2-CONTRACTS.md section 1.1).
 * Kept for API-layer completeness; the M2 debug page scope (section 5) only lists a rewrite
 * on/off switch, not a multi-turn message composer, so this type is not wired into any form yet.
 */
export interface ChatMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
}

/**
 * Extra fields the M2 retrieval pipeline adds onto RetrievalNode.metadata (M2-CONTRACTS.md
 * section 1.5): "响应 nodes[].metadata 增：norm_vector_score/norm_bm25_score/fused_score/rerank_score（存在时）".
 * ASSUMPTION: the raw (pre-normalization) per-route scores are not literally named in the contract,
 * but norm_vector_score/norm_bm25_score only make sense as normalized derivatives of raw
 * vector_score/bm25_score, so those two keys are assumed present alongside them when the
 * corresponding route contributed to the candidate. child_ids/children shape (parent/child
 * mode, section 1.4 "metadata 含 child_ids、每子片各路分") is likewise not given a concrete
 * schema by the contract; the shape below is this implementation's best-effort inference.
 */
export interface RetrievalChildHit {
  chunk_id: string;
  content: string;
  score: number;
  score_type: ScoreType;
  vector_score?: number;
  bm25_score?: number;
  norm_vector_score?: number;
  norm_bm25_score?: number;
  fused_score?: number;
  rerank_score?: number;
  [key: string]: unknown;
}

export interface RetrievalNodeMetadata {
  tag_ids?: string[];
  session_id?: string;
  /** chat_log only (M3-CONTRACTS.md section 3.5): the session's display name. */
  session_name?: string;
  sender?: string;
  msg_time?: number;
  vector_score?: number;
  bm25_score?: number;
  norm_vector_score?: number;
  norm_bm25_score?: number;
  fused_score?: number;
  rerank_score?: number;
  /** Present only in parent/child mode: ids of the child chunks merged into this parent node. */
  child_ids?: string[];
  /** Present only in parent/child mode: per-child-chunk score detail, see RetrievalChildHit. */
  children?: RetrievalChildHit[];
  [key: string]: unknown;
}

/**
 * RetrievalNode, see M1-CONTRACTS.md section 5, extended by M2-CONTRACTS.md section 1.5 and
 * M3-CONTRACTS.md section 4 (chunk_type-driven display: image/chat_log tags, image_urls thumbnails).
 */
export interface RetrievalNode {
  doc_id: string;
  document_version_id: string;
  chunk_id: string;
  chunk_type: ChunkType;
  content: string;
  score: number;
  score_type: ScoreType;
  retrieval_source: RetrievalSource;
  metadata: RetrievalNodeMetadata | null;
  image_urls: string[];
  preview_url: string | null;
}

export interface SearchRequest {
  query: string;
  recall_top_k: number;
  top_n: number;
  /** 0.01-1.0, omit/null = no filtering (M2-CONTRACTS.md section 1.3). */
  score_threshold?: number | null;
  fusion?: FusionConfig;
  /** Default true when a rerank model is configured (M2-CONTRACTS.md section 1.5). */
  rerank_enabled?: boolean;
  /** Default false (M2-CONTRACTS.md section 1.5). */
  rewrite_enabled?: boolean;
  messages?: ChatMessage[];
  metadata_filter?: MetadataFilter;
}

/** Top-level "actually applied" info bar payload (M2-CONTRACTS.md section 1.5). */
/** Value of applied.threshold_applied_on; 'none' means no threshold filtered the result set. */
export type ThresholdAppliedOn = ScoreType | 'none';

export interface SearchApplied {
  rewrite_used_query: string | null;
  fusion_mode: FusionMode;
  threshold_applied_on: ThresholdAppliedOn | null;
}

export interface SearchResponse {
  nodes: RetrievalNode[];
  request_id: string;
  degraded: string[];
  applied: SearchApplied;
}

// ---------------------------------------------------------------------------
// System / model status
// ---------------------------------------------------------------------------

/** Per-model-kind configuration snapshot, used by the M2 settings page's three status cards. */
/**
 * GET /api/v1/system/model-status response, extended for M2 (M2-CONTRACTS.md section 5
 * "模型状态卡片（embedding/rerank/chat 三卡，用扩展后的 model-status）").
 * ASSUMPTION: the concrete extended JSON shape isn't spelled out by the contract; the
 * top-level embedding_configured/provider/model fields are kept as-is for backward
 * compatibility with existing M1 call sites (MainLayout/SearchPage banners), and a
 * per-kind breakdown is added for the three cards.
 */
export interface ModelStatus {
  embedding_configured: boolean;
  provider: string | null;
  model: string | null;
  rerank_configured: boolean;
  rerank_provider: string | null;
  rerank_model: string | null;
  chat_configured: boolean;
  chat_provider: string | null;
  chat_model: string | null;
  vector_engine: string;
  /** M3-CONTRACTS.md section 3.1: qwen-vl-max image understanding model used by the M3 image pipeline. */
  vision_configured: boolean;
  vision_provider: string | null;
  vision_model: string | null;
}

/** View model assembled from the flat ModelStatus fields, one per model capability. */
export interface ModelProviderView {
  configured: boolean;
  provider: string | null;
  model: string | null;
}

// ---------------------------------------------------------------------------
// ik dictionary (M2-CONTRACTS.md section 3)
// ---------------------------------------------------------------------------

export type IkDictType = 'EXT' | 'STOP';
export type IkDictStatus = 'ENABLED' | 'DISABLED';

/**
 * t_kb_ik_dict row. ASSUMPTION: the table has no dedicated business `xxx_id` column per the
 * DDL sketch in M2-CONTRACTS.md section 3 ("word UK, dict_type=EXT|STOP, status=ENABLED/DISABLED,
 * 通用列"), so `word` (the unique key) is used as the entry identifier for delete/status calls
 * instead of an unexposed internal auto-increment id.
 */
export interface IkDictEntry {
  word: string;
  dict_type: IkDictType;
  status: IkDictStatus;
  created_at: string;
  updated_at: string;
}

export interface CreateIkDictEntryRequest {
  word: string;
  dict_type: IkDictType;
}

// ---------------------------------------------------------------------------
// Parse preview / confirm (M3-CONTRACTS.md section 3.4)
// ---------------------------------------------------------------------------

/** parser response pages[] entry, mirrored from kb-rag-parser (M3-CONTRACTS.md section 2.1). */
export interface ParsedPage {
  page_no: number;
  text: string;
  scanned: boolean;
}

/** One image entry inside GET /documents/{docId}/preview (M3-CONTRACTS.md section 3.4). */
export interface DocumentPreviewImage {
  image_id: string;
  preview_url: string;
  text_proxy: string;
}

/** GET /api/v1/documents/{docId}/preview response. */
export interface DocumentPreview {
  markdown: string;
  pages: ParsedPage[];
  images: DocumentPreviewImage[];
  warnings: string[];
}

/**
 * POST /api/v1/documents/{docId}/reparse request body (M3-CONTRACTS.md section 3.4): an optional
 * clean_rules override used only for this preview run, never persisted to the KB's index_config.
 */
export interface ReparseDocumentRequest {
  clean_rules?: CleanRules;
}

/** POST /api/v1/kb/{kbId}/documents/confirm request body; omitted doc_ids = all PENDING_CONFIRM docs in the KB. */
export interface ConfirmDocumentsRequest {
  doc_ids?: string[];
}

// ---------------------------------------------------------------------------
// Chat log import (M3-CONTRACTS.md section 3.5)
// ---------------------------------------------------------------------------

export type ChatImportAction = 'CREATE' | 'NEW_VERSION';

/**
 * ASSUMPTION: the response sketch only says "时间范围" without naming keys; modelled as an
 * epoch-millis [from, to] pair to mirror msg_time (RetrievalNodeMetadata/MetadataFilter above).
 */
export interface ChatImportTimeRange {
  from: number;
  to: number;
}

export interface ChatImportSessionPreview {
  session_id: string;
  session_name: string;
  message_count: number;
  time_range: ChatImportTimeRange;
  matched_doc_id: string | null;
  action: ChatImportAction;
}

/**
 * POST /api/v1/kb/{kbId}/chat-imports response.
 * ASSUMPTION: the contract's JSON sketch only shows the `sessions` array, but the confirm step
 * requires an `upload_token` in its request body and the parsed upload is "暂存 MinIO...30 分钟",
 * so the preview response must hand that token back; assumed to sit alongside `sessions`.
 */
export interface ChatImportPreviewResponse {
  upload_token: string;
  sessions: ChatImportSessionPreview[];
}

/** POST /api/v1/kb/{kbId}/chat-imports/confirm request body. */
export interface ConfirmChatImportRequest {
  upload_token: string;
  /** Optional subset of session_ids to import; omitted = import every previewed session. */
  session_ids?: string[];
}

// ---------------------------------------------------------------------------
// Alert webhook (M3-CONTRACTS.md section 3.6)
// ---------------------------------------------------------------------------

export interface AlertConfig {
  webhook_url: string | null;
  enabled: boolean;
  task_fail_threshold: number;
  /** 0-1 ratio, e.g. 0.3 = 30% degrade rate over the trailing 5 minutes. */
  degrade_rate_threshold: number;
  sync_backlog_threshold: number;
  /** Minutes; same alert type is not resent again within this window. */
  silence_minutes: number;
}

export type UpdateAlertConfigRequest = AlertConfig;

// ---------------------------------------------------------------------------
// Demo one-click import (M3-CONTRACTS.md section 3.7)
// ---------------------------------------------------------------------------

export interface DemoStatus {
  available: boolean;
  imported: boolean;
  kb_id: string | null;
  doc_count: number;
}

/**
 * POST /api/v1/system/demo/import response.
 * ASSUMPTION: the contract only specifies "返回其 kb_id" for the idempotent repeat-call case;
 * modelled as a minimal {kb_id} shape since the web action only needs the id to navigate to the
 * KB detail page, not a full KnowledgeBase payload.
 */
export interface DemoImportResult {
  kb_id: string;
}

// ---------------------------------------------------------------------------
// Document version management (M4a-CONTRACTS.md section 1)
// ---------------------------------------------------------------------------

/** t_kb_document_version.status (M4a-CONTRACTS.md section 0, already delivered by the M1 baseline). */
export type DocumentVersionStatus = 'BUILDING' | 'BUILD_FAILED' | 'READY' | 'ACTIVE' | 'ARCHIVED';

/**
 * Activation switch-over strategy (M4a-CONTRACTS.md section 1.2): INSTANT when the target
 * version is still READY with its chunk rows intact (atomic swap, returns immediately); REBUILD
 * when the target is ARCHIVED and its chunks were already cleaned up (re-derives chunks from the
 * stored parse artifact via an async task).
 */
export type RollbackMode = 'INSTANT' | 'REBUILD';

/** GET /api/v1/documents/{docId}/versions list item (M4a-CONTRACTS.md section 1.2). */
export interface DocumentVersion {
  version_id: string;
  version: string;
  status: DocumentVersionStatus;
  content_hash: string;
  created_at: string;
  changelog: string | null;
  active: boolean;
  chunk_count: number;
  rollback_mode: RollbackMode;
}

/**
 * GET /api/v1/documents/{docId}/versions/{versionId}/activate-impact response (M4a-CONTRACTS.md
 * section 1.2): pre-flight check surfaced in the activation confirm dialog.
 * affected_eval_case_count is a placeholder that always returns 0 in M4a (eval sets ship in M4b).
 */
export interface ActivateImpact {
  stale_annotation_count: number;
  affected_eval_case_count: number;
  rollback_mode: RollbackMode;
  needs_rebuild: boolean;
}

/**
 * POST /api/v1/documents/{docId}/versions/{versionId}/activate response.
 * ASSUMPTION: the contract only spells out the REBUILD-mode body ("返回 {task_id}"); the
 * INSTANT-mode response shape ("同步原子切换并立即返回") is not given, so it is modelled here as
 * task_id: null so both branches share one response type and the caller only needs to check
 * whether task_id is present to decide whether to enter the polling flow.
 */
export interface ActivateVersionResponse {
  task_id: string | null;
}

// ---------------------------------------------------------------------------
// Chunk annotation (M4a-CONTRACTS.md section 2)
// ---------------------------------------------------------------------------

/** t_kb_annotation.annotation_type (M4a-CONTRACTS.md section 2.4). */
export type AnnotationType = 'EDIT' | 'TOGGLE' | 'MERGE' | 'SPLIT';

/**
 * t_kb_annotation.inherit_status (M4a-CONTRACTS.md sections 2.3/2.4): whether this old-version
 * annotation carried over to the new active version automatically, was manually redone there, or
 * was not carried over at all.
 */
export type InheritStatus = 'NOT_INHERITED' | 'AUTO_INHERITED' | 'REDONE';

/**
 * t_kb_annotation.payload JSON (M4a-CONTRACTS.md section 2.4): "合并的来源 id 列表、拆分偏移、
 * 编辑前后摘录、启用状态". Shape varies by annotation_type -- only the fields matching the row's
 * annotation_type are populated by the server, so every field is optional here.
 */
export interface AnnotationPayload {
  /** EDIT: content excerpt before the edit. */
  before_excerpt?: string;
  /** EDIT: content excerpt after the edit. */
  after_excerpt?: string;
  /** TOGGLE: the resulting enabled state. */
  enabled?: boolean;
  /** MERGE: source chunk ids that were merged into the new chunk. */
  source_chunk_ids?: string[];
  /** SPLIT: character offsets the original chunk was split at. */
  split_offsets?: number[];
  [key: string]: unknown;
}

/**
 * t_kb_annotation row, returned by GET /api/v1/documents/{docId}/annotations/pending-review
 * (M4a-CONTRACTS.md section 2.3): the old-version annotation list shown before a stale-annotation
 * activation warning is expanded.
 */
export interface Annotation {
  annotation_id: string;
  kb_id: string;
  doc_id: string;
  document_version_id: string;
  chunk_id: string;
  annotation_type: AnnotationType;
  payload: AnnotationPayload;
  chunk_text_hash: string;
  inherit_status: InheritStatus;
  operator: string;
  created_at: string;
  updated_at: string;
}

/** PUT /api/v1/chunks/{chunkId} request body (M4a-CONTRACTS.md section 2.1): in-place content edit. */
export interface EditChunkRequest {
  content: string;
}

/** POST /api/v1/chunks/{chunkId}/toggle request body (M4a-CONTRACTS.md section 2.1). */
export interface ToggleChunkRequest {
  enabled: boolean;
}

/** POST /api/v1/chunks/merge request body (M4a-CONTRACTS.md section 2.1). */
export interface MergeChunksRequest {
  chunk_ids: string[];
}

/** POST /api/v1/chunks/{chunkId}/split request body (M4a-CONTRACTS.md section 2.1). */
export interface SplitChunkRequest {
  split_offsets: number[];
}

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
  /**
   * M7-CONTRACTS.md section 0.1/0.10: knowledge-base-level GraphRAG switch, stored in the
   * server's KnowledgeBase.retrievalConfig JSON (default false). ASSUMPTION: returned inline on
   * GET /kb and GET /kb/{kbId} -- same precedent as index_config/current_config_fingerprint above
   * -- so the fusion-mode mutex hint in SearchPage/AppConfigTab doesn't need a dedicated per-kb
   * round trip just to know whether graph routing is on. Absent on pre-M7 responses; read as
   * `kb.graph_enabled ?? false`.
   */
  graph_enabled?: boolean;
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
  /**
   * M5-CONTRACTS.md section 2.2: which kb this node came from. Populated on the multi-kb-routed
   * endpoints (chat-preview, external knowledge/search, knowledge/chat); the admin single-kb debug
   * search endpoint is unchanged and does not need it (always the page's one kb).
   */
  kb_id?: string;
  /**
   * M7-CONTRACTS.md section 0.8: graph route's path-internal ranking score (entity match score ×
   * 1/(1+hops)), present only when the graph route contributed to this node (multi-entity hits on
   * the same chunk already take the max on the server side per section 0.5).
   */
  graph_score?: number;
  /** M7-CONTRACTS.md section 0.8: hop count from the query-matched entity to this chunk's source. */
  graph_hops?: number;
  /** M7-CONTRACTS.md section 0.8: matched entity names behind this hit, capped at 5. */
  graph_entities?: string[];
  /**
   * M8-CONTRACTS.md section 0.5: chat aggregation overlapping-window sequence number for this
   * chunk (only meaningful when the kb's `window_overlap` > 0; absent under the pre-M8 default
   * window_overlap=0 straight-cut behavior).
   */
  window_seq?: number;
  /**
   * M8-CONTRACTS.md section 0.5: inclusive [start, end] message-sequence span within the session
   * that this chunk's window covers.
   */
  msg_span?: [number, number];
  /**
   * M8-CONTRACTS.md section 0.6: near-duplicate window merge (retrieval side, applied after
   * library-fusion and before rerank) -- ids of the lower-ranked chunks (same session_id,
   * msg_span overlap ratio >= 0.5) folded into this node, capped at 5 by the server. Presence
   * means at least one overlapping window was merged away; RetrievalNodeCard renders a
   * "已归并重叠窗口 ×N" hint line sized off this array's length.
   */
  merged_window_chunk_ids?: string[];
  /**
   * M9-CONTRACTS.md section 0.3: present only when the server actually redacted disabled-child
   * text out of this parent node (hide_parent_with_disabled_child=false kb, at least one disabled
   * child chunk with non-null parent offsets) -- counts how many child segments were cut. Absent
   * both when nothing was redacted and when a null-offset child forced the whole-parent fallback
   * (section 0.3's "任一禁用子片偏移为 null -> 整片回退现状"), since that fallback returns the
   * parent unmodified with no redaction to report.
   */
  redacted_child_count?: number;
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
  /**
   * M5-CONTRACTS.md section 2.2: kb ids actually searched this call (single-kb apps still report
   * their one kb here). Optional so responses from a pre-M5 backend still type-check.
   */
  routed_kb_ids?: string[];
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
// Source mapping (M8-CONTRACTS.md section 0.7): t_kb_source_mapping CRUD -- backs both the
// system-settings "导入映射" tab and the chat-import wizard's mapping-profile picker below.
// ---------------------------------------------------------------------------

/**
 * t_kb_source_mapping.source_type (M8-CONTRACTS.md section 0.7: "csv/xlsx/txt/html"); csv/xlsx
 * rows predate this milestone (table "一期已就位"), txt/html are the two formats M8 adds.
 */
export type SourceMappingType = 'csv' | 'xlsx' | 'txt' | 'html';

/**
 * t_kb_source_mapping row (M8-CONTRACTS.md section 0.7: "行含 name UK、source_type(csv/xlsx/
 * txt/html)、profile_yaml 文本、is_builtin"; built-ins are seeded idempotently from the parser's
 * local yml at server startup and are "不可删可复制").
 * ASSUMPTION: the id column name is not spelled out anywhere except the `{mappingId}` path
 * template on the PUT/DELETE routes; `mapping_id` is assumed by symmetry with every other
 * `xxx_id` business identifier in this codebase (key_id/app_id/kb_id/doc_id/...). created_at/
 * updated_at are likewise assumed present by symmetry with every other managed-entity row
 * (IkDictEntry/ApiKey/...) even though the contract's row sketch omits timestamps.
 */
export interface SourceMapping {
  mapping_id: string;
  name: string;
  source_type: SourceMappingType;
  profile_yaml: string;
  is_builtin: boolean;
  created_at: string;
  updated_at: string;
}

/** POST /api/v1/source-mappings request body: create a custom mapping (is_builtin is always server-assigned false). */
export interface CreateSourceMappingRequest {
  name: string;
  source_type: SourceMappingType;
  profile_yaml: string;
}

/**
 * PUT /api/v1/source-mappings/{mappingId} request body.
 * ASSUMPTION: built-ins are read-only per section 0.7 ("不可改"), so the web only ever calls this
 * on a custom row; the shape mirrors CreateSourceMappingRequest since the contract does not carve
 * out a narrower partial-update shape (e.g. profile_yaml only).
 */
export type UpdateSourceMappingRequest = CreateSourceMappingRequest;

/**
 * POST /api/v1/source-mappings/{mappingId}/copy request body -- "复制为自定义" action on a
 * built-in row (section 0.7).
 * ASSUMPTION/GAP: the contract only states built-ins "可复制" without naming an endpoint or
 * request shape; modelled as its own action route (mirrors rotateApiKey's POST .../rotate
 * pattern elsewhere in this codebase) rather than overloading POST /source-mappings, since the
 * input here is "which built-in to clone" rather than a from-scratch profile_yaml. Flagged for
 * reconciliation with the server agent's actual route.
 */
export interface CopySourceMappingRequest {
  /** Optional display name for the copy; omitted lets the server derive one (e.g. "{name} 副本"). */
  name?: string;
}

// ---------------------------------------------------------------------------
// Chat log import (M3-CONTRACTS.md section 3.5, mapping picker extended by M8-CONTRACTS.md section 0.7)
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

/**
 * GET /api/v1/documents/{docId}/versions list item (M4a-CONTRACTS.md section 1.2), extended by
 * M6-CONTRACTS.md section 0.8/2 with the AppVersionPinChecker's pin state.
 * ASSUMPTION: M6-CONTRACTS.md section 1 names these two fields but not their exact JSON shape
 * beyond "pinned（boolean...）与 pinned_by（引用它的 app_version_id 列表...）"; modelled as-is here.
 * pinned/pinned_by are display-only in this web -- archival/cleanup itself has no manual web entry
 * point (it is VersionRetentionService's automatic retention sweep, M4a-CONTRACTS.md section 1.3),
 * so there is nothing here for a pinned version to disable; the server-side pin check is what
 * actually blocks the automatic cleanup from touching this version.
 */
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
  /** True when any not-yet-cleaned application version's visible set still references this version. */
  pinned?: boolean;
  /** app_version_id list pinning this version; tooltip display only. Empty/absent when not pinned. */
  pinned_by?: string[];
}

/**
 * GET /api/v1/documents/{docId}/versions/{versionId}/activate-impact response (M4a-CONTRACTS.md
 * section 1.2): pre-flight check surfaced in the activation confirm dialog.
 * affected_eval_case_count was a placeholder that always returned 0 in M4a; M4b-CONTRACTS.md
 * section 0 fills it in with the real count of span-level eval cases anchored to this doc_id that
 * will flip to EVIDENCE_STALE once this version becomes active.
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
 * One migration candidate on a pending-review row (M9-CONTRACTS.md section 0.5): lazily computed
 * per request (not persisted/cached -- the pending list is inherently low-traffic), ranked by the
 * symmetric character 3-gram Dice coefficient (section 0.4) against every enabled chunk in the
 * newly active version, top 3 with score >= kb.annotation.migration-min-score (default 0.35).
 */
export interface AnnotationMigrationSuggestion {
  chunk_id: string;
  /** Truncated to <=120 characters server-side. */
  content_preview: string;
  /** Symmetric 3-gram Dice coefficient, 0..1 -- render as a percentage, never a raw 4-decimal score. */
  score: number;
}

/**
 * t_kb_annotation row, returned by GET /api/v1/documents/{docId}/annotations/pending-review
 * (M4a-CONTRACTS.md section 2.3, suggestions added by M9-CONTRACTS.md section 0.5): the old-version
 * annotation list shown before a stale-annotation activation warning is expanded. Renamed from the
 * M4a-era `Annotation` to `PendingAnnotation` since this row shape is only ever returned by this one
 * pending-review endpoint (not a general-purpose annotation type).
 */
export interface PendingAnnotation {
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
  /**
   * M9-CONTRACTS.md section 0.5. ASSUMPTION: the contract's response sketch only names the field
   * without stating whether it is always present; modelled as always-present (server returns `[]`
   * rather than omitting the key) since section 0.4 guarantees a short-text row still gets a
   * (empty) verdict, not an absent field -- the pending-review workbench renders "无相似候选" off an
   * empty array, not a missing key.
   */
  suggestions: AnnotationMigrationSuggestion[];
}

/**
 * POST /api/v1/annotations/{annotationId}/migrate request body (M9-CONTRACTS.md section 0.5):
 * applies the pending row's edit/disable semantics onto `target_chunk_id` in the newly active
 * version and marks the original pending row processed; idempotent when repeated with the same
 * target. ASSUMPTION: the response shape is not spelled out; the workbench does not need it beyond
 * success/failure since it reloads the pending list afterwards (same pattern as editChunk/
 * activateVersion elsewhere in this codebase), so the call is typed as returning void.
 */
export interface MigrateAnnotationRequest {
  target_chunk_id: string;
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

// ---------------------------------------------------------------------------
// Evaluation center (M4b-CONTRACTS.md sections 1/2/3)
// ---------------------------------------------------------------------------

/** t_kb_eval_case.anchor_type (M4b-CONTRACTS.md section 1). */
export type AnchorType = 'SPAN' | 'DOCUMENT';

/**
 * t_kb_eval_case.status (M4b-CONTRACTS.md section 1): unrelated to M4a's Annotation.inherit_status
 * despite the similar naming -- do not reuse INHERIT_STATUS_META for this enum.
 */
export type CaseStatus = 'ACTIVE' | 'EVIDENCE_STALE' | 'DEPRECATED';

/** t_kb_eval_case.source (M4b-CONTRACTS.md section 1). */
export type CaseSource = 'MANUAL' | 'DEBUG_PAGE' | 'IMPORTED';

/** t_kb_eval_run.status (M4b-CONTRACTS.md section 1). */
export type RunStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';

/** Run config matrix mode (M4b-CONTRACTS.md section 3.1), maps to existing retrieval parameters server-side. */
export type EvalMode = 'BM25_ONLY' | 'VECTOR_ONLY' | 'HYBRID' | 'HYBRID_RERANK';

/** Report grouping key (M4b-CONTRACTS.md section 3.3: "分组输出：全体/span级/文档级/单轮/多轮"). */
export type MetricGroupKey = 'all' | 'span' | 'document' | 'single_turn' | 'multi_turn';

/**
 * t_kb_eval_case.evidences[] element (M4b-CONTRACTS.md section 1): `span` is null/empty when
 * anchor_type=DOCUMENT. `annotated_version_id` is filled server-side from the doc's active version
 * at write time and is never supplied by the client.
 */
export interface EvalCaseEvidence {
  doc_id: string;
  span: string | null;
  annotated_version_id: string | null;
}

/** Client-submitted evidence shape (create/edit/recheck request bodies), see M4b-CONTRACTS.md section 2. */
export interface EvalCaseEvidenceInput {
  doc_id: string;
  span?: string;
}

/**
 * t_kb_eval_dataset row, extended with the list view's derived "最近一次 run 摘要"
 * (M4b-CONTRACTS.md section 2 dataset list bullet).
 * ASSUMPTION: the exact JSON key/shape of the run summary embedded in the list response is not
 * spelled out by the contract beyond "最近一次 run 摘要"; modelled as a minimal
 * {run_id, status, mode, finished_at} so the dataset table can show a status Tag without a
 * second round trip per row.
 */
export interface EvalDatasetRunSummary {
  run_id: string;
  status: RunStatus;
  mode: EvalMode | null;
  finished_at: string | null;
}

export interface EvalDataset {
  dataset_id: string;
  kb_id: string;
  name: string;
  description: string | null;
  dataset_revision: number;
  case_count: number;
  last_run: EvalDatasetRunSummary | null;
  created_at: string;
  updated_at: string;
}

/** POST /api/v1/kb/{kbId}/eval-datasets request body (M4b-CONTRACTS.md section 2). */
export interface CreateEvalDatasetRequest {
  name: string;
  description?: string;
}

/** t_kb_eval_case row (M4b-CONTRACTS.md section 1). */
export interface EvalCase {
  case_id: string;
  dataset_id: string;
  query: string;
  messages: ChatMessage[] | null;
  expected_answer: string | null;
  anchor_type: AnchorType;
  evidences: EvalCaseEvidence[];
  status: CaseStatus;
  source: CaseSource;
  note: string | null;
  created_at: string;
  updated_at: string;
}

/**
 * POST /api/v1/eval-datasets/{datasetId}/cases request body (M4b-CONTRACTS.md section 2); also
 * reused as the PUT /api/v1/eval-cases/{caseId} edit body (same shape per the contract's "编辑"
 * bullet, no separate schema given).
 */
export interface CreateEvalCaseRequest {
  query: string;
  messages?: ChatMessage[];
  expected_answer?: string;
  anchor_type: AnchorType;
  evidences: EvalCaseEvidenceInput[];
  note?: string;
}

export type UpdateEvalCaseRequest = CreateEvalCaseRequest;

/** POST /api/v1/eval-cases/{caseId}/recheck request body (M4b-CONTRACTS.md section 2). */
export type RecheckCaseAction = 'REANCHOR' | 'DEPRECATE';

export interface RecheckCaseRequest {
  action: RecheckCaseAction;
  /** Required for REANCHOR (the case's full replacement evidence list); omitted for DEPRECATE. */
  evidences?: EvalCaseEvidenceInput[];
}

/** POST /api/v1/eval-datasets/{datasetId}/cases/from-retrieval request body (M4b-CONTRACTS.md section 2). */
export interface CreateEvalCaseFromRetrievalRequest {
  query: string;
  messages?: ChatMessage[];
  chunk_ids: string[];
  /** Omit to let the server auto-detect (image chunk_type -> DOCUMENT); pass 'DOCUMENT' to force it. */
  anchor_type?: AnchorType;
}

/**
 * One Top-3 replacement candidate for a stale evidence, surfaced by GET
 * .../stale-cases (M4b-CONTRACTS.md section 2 "按重叠率取 Top3 候选供人工选择").
 * ASSUMPTION: exact field names are not given by the contract; modelled with the same
 * doc_id/span vocabulary as EvalCaseEvidence plus the source chunk_id (needed as a stable React
 * key and for potential future traceability) and overlap_ratio (the number the Top3 ranking and
 * the 0.5 threshold from section 3.2 are computed from).
 */
export interface StaleEvidenceCandidate {
  doc_id: string;
  chunk_id: string;
  span: string;
  overlap_ratio: number;
}

/**
 * ASSUMPTION: pairs one stale (no-longer-matching) evidence of a case with its Top-3 replacement
 * candidates; exact response shape is not given beyond the section 2 prose description.
 */
export interface StaleEvidenceReview {
  evidence: EvalCaseEvidence;
  candidates: StaleEvidenceCandidate[];
}

/** GET /api/v1/eval-datasets/{datasetId}/stale-cases response item (M4b-CONTRACTS.md section 2). */
export interface StaleCaseItem {
  case: EvalCase;
  stale_evidences: StaleEvidenceReview[];
}

/**
 * POST /api/v1/kb/{kbId}/eval-datasets/import-demo response (M4b-CONTRACTS.md section 2).
 * ASSUMPTION: the contract only describes the behavior ("匹配不到的 case 跳过并在响应列出，幂等");
 * modelled as the created/existing dataset id plus an import-count and a skipped-case reason list.
 */
export interface ImportDemoEvalDatasetSkippedCase {
  case_index: number;
  reason: string;
}

export interface ImportDemoEvalDatasetResult {
  dataset_id: string;
  /** true when import-demo was a no-op repeat call against an already-imported dataset. */
  already_existed: boolean;
  imported_case_count: number;
  skipped: ImportDemoEvalDatasetSkippedCase[];
}

/** One run config entry, both as submitted in CreateEvalRunRequest.configs and as stored in EvalRun.retrieval_config (M4b-CONTRACTS.md section 3.1). */
export interface EvalRunConfig {
  label: string;
  mode: EvalMode;
  recall_top_k?: number;
  top_n?: number;
  /**
   * Fusion strategy LITERAL ('rrf' | 'weighted'), matching the server's EvalRunSubmitRequest.
   * The earlier FusionConfig object shape 500ed every HYBRID/HYBRID_RERANK estimate/submit
   * (Jackson: cannot deserialize String from Object) -- the field was never an object server-side.
   */
  fusion?: FusionMode;
  score_threshold?: number | null;
  rewrite_enabled?: boolean;
}

export interface EvalJudgeConfig {
  enabled: boolean;
  model?: string;
}

/** POST /api/v1/eval-datasets/{datasetId}/runs and .../runs/estimate request body (M4b-CONTRACTS.md section 3.1). */
export interface CreateEvalRunRequest {
  k: number;
  /** 1..6 entries; one run is created per entry (section 3.1). */
  configs: EvalRunConfig[];
  judge?: EvalJudgeConfig;
}

/**
 * POST /api/v1/eval-datasets/{datasetId}/runs/estimate response (M4b-CONTRACTS.md section 3.4
 * "返回预估调用次数（嵌入/重排/改写/judge 各自次数）").
 * ASSUMPTION: field names are not spelled out beyond that prose; named to mirror the four call
 * kinds it lists in that order.
 */
export interface EvalRunEstimate {
  embedding_calls: number;
  rerank_calls: number;
  rewrite_calls: number;
  judge_calls: number;
}

/** One (value, optional Wilson 95% CI) metric point (M4b-CONTRACTS.md section 3.3). CI is present only for proportion-type metrics (recall/precision/hit_rate), not MRR/NDCG. */
/** Wilson interval bounds as the server emits them (EvalMetricsAtK: {low, high}). */
export interface MetricCiBounds {
  low: number;
  high: number;
}

/** Metric keys that hold a plain number in KMetricSet. */
export type MetricNumberKey = 'recall' | 'precision' | 'hit_rate' | 'mrr' | 'ndcg';

/**
 * The five metrics computed per K per group (M4b-CONTRACTS.md section 3.3), in the FLAT shape the
 * server actually serialises: plain numbers plus sibling `*_ci` interval objects for the two
 * proportion metrics. The earlier per-metric {value, ci_low, ci_high} wrapper was an unreconciled
 * assumption and rendered every cell as NaN% against the real backend.
 */
export interface KMetricSet {
  recall: number;
  precision: number;
  hit_rate: number;
  mrr: number;
  ndcg: number;
  recall_ci?: MetricCiBounds;
  hit_rate_ci?: MetricCiBounds;
}

/**
 * t_kb_eval_run.metrics JSON (M4b-CONTRACTS.md section 3.3): keyed by group then by K (K as a
 * string key, e.g. "5") since a run can report metrics for more than one K per group.
 * Shape verified against the live backend (2026-07-27): group -> K(string) -> flat KMetricSet.
 */
export type EvalMetrics = Partial<Record<MetricGroupKey, Record<string, KMetricSet>>>;

/** t_kb_eval_run row (M4b-CONTRACTS.md section 1/3.1). */
export interface EvalRun {
  run_id: string;
  dataset_id: string;
  kb_id: string;
  dataset_revision: number;
  corpus_fingerprint: string;
  retrieval_config: EvalRunConfig;
  judge_model: string | null;
  judge_prompt_version: string | null;
  status: RunStatus;
  metrics: EvalMetrics | null;
  case_total: number;
  case_effective: number;
  case_stale: number;
  case_degraded: number;
  fail_reason: string | null;
  started_at: string | null;
  finished_at: string | null;
  created_at: string;
  updated_at: string;
}

/** t_kb_eval_result row (M4b-CONTRACTS.md section 1), used by the report's per-case drill-down table. */
export interface EvalResult {
  result_id: string;
  run_id: string;
  case_id: string;
  hit: boolean;
  hit_rank: number | null;
  overlap_ratios: number[];
  recalled_chunk_ids: string[];
  degraded: string[];
  retry_count: number;
  judge_score: number | null;
  judge_reason: string | null;
  created_at: string;
  updated_at: string;
}

/**
 * GET /api/v1/eval-runs/compare response (M4b-CONTRACTS.md section 3.1): when comparable=false
 * (different dataset_revision across the requested runs) `runs` is not populated and `reason`
 * explains why; when comparable=true, `runs` carries each run's full row (including `metrics`) so
 * the report can zip them into one side-by-side table.
 */
export interface EvalRunCompareResult {
  comparable: boolean;
  reason: string | null;
  runs: EvalRun[];
}

// ---------------------------------------------------------------------------
// Application center: apps, versions, release gate (M4c-CONTRACTS.md sections 1/2)
// ---------------------------------------------------------------------------

/** t_kb_app row (M4c-CONTRACTS.md section 1). */
export interface KbApp {
  app_id: string;
  name: string;
  description: string | null;
  created_at: string;
  updated_at: string;
}

export interface CreateAppRequest {
  name: string;
  description?: string;
}

/**
 * ASSUMPTION: section 2 only lists the CRUD path prefix ("应用 CRUD `/api/v1/apps`") without
 * spelling out which verbs beyond create; a plain PUT for name/description edits mirrors every
 * other resource's CRUD shape in this codebase (e.g. kb.ts's create/get/delete trio).
 */
export type UpdateAppRequest = CreateAppRequest;

/**
 * t_kb_app_version.config JSON (M4c-CONTRACTS.md section 1): "发布时固化的全部检索+问答配置快照".
 * Editable while the owning version is DRAFT/TESTING; frozen once RELEASED (rollback re-releases
 * the historical row's config verbatim, never re-derives it).
 */
export interface AppRetrievalConfig {
  recall_top_k: number;
  top_n: number;
  score_threshold?: number | null;
  /**
   * FLAT fusion fields, matching the server snapshot (KbRetrievalConfig: fusion_mode/w_vec/rrf_k).
   * The earlier nested `fusion` object was silently dropped by the server on save (unknown field)
   * and never present on read -- the app-center fusion setting was a no-op end to end.
   */
  fusion_mode?: FusionMode;
  w_vec?: number;
  rrf_k?: number;
  rerank_enabled?: boolean;
  rewrite_enabled?: boolean;
}

/**
 * Prompt config sub-object named field-by-field in M4c-CONTRACTS.md section 1: "{system_prompt,
 * refusal_enabled,refusal_prompt,leak_guard_enabled,leak_guard_prompt,citation_enabled}".
 */
export interface AppPromptConfig {
  system_prompt: string;
  refusal_enabled: boolean;
  refusal_prompt: string;
  leak_guard_enabled: boolean;
  leak_guard_prompt: string;
  citation_enabled: boolean;
}

/**
 * t_kb_app_version.config.kb_refs entry (M5-CONTRACTS.md section 1): one knowledge base an app
 * version is bound to, plus its share of the rerank-candidate quota when the app spans several
 * kbs. weight is a positive integer, default 1; a single-kb version's one entry always gets the
 * full quota regardless of its weight value (M5-CONTRACTS.md section 2.2).
 */
export interface KbRef {
  kb_id: string;
  weight: number;
}

/**
 * t_kb_app_version.config.routing (M5-CONTRACTS.md sections 1/2.1): LLM-judged per-query kb
 * selection, only actually invoked when enabled and the version has >=2 kb_refs (a single-kb
 * version never calls the router even if this is left on).
 */
export interface AppRoutingConfig {
  enabled: boolean;
  /** null/empty = server falls back to its built-in default routing prompt. */
  prompt: string | null;
}

export interface AppVersionConfig {
  /** 1..15 entries (M5-CONTRACTS.md section 1), replacing M4c's single kb_id. */
  kb_refs: KbRef[];
  retrieval: AppRetrievalConfig;
  prompt: AppPromptConfig;
  /** Absent on pre-M5 snapshots; read as `config.routing?.enabled ?? false` / `?.prompt ?? null`. */
  routing?: AppRoutingConfig;
  /**
   * @deprecated M4c-era single-kb snapshot field. Present only on versions created before M5;
   * never populated by this web's write path. Do not read directly -- go through
   * resolveKbRefs() (utils/kbRefs.ts) so the M4c/M5 shapes are normalized in exactly one place
   * (M5-CONTRACTS.md section 1: "读侧兼容旧快照的单 kb_id 字段").
   */
  kb_id?: string;
}

/**
 * t_kb_app_version.status (M4c-CONTRACTS.md section 1): the eight-state release/gate machine.
 * Must always be displayed through APP_VERSION_STATUS_META/metaOf, never a raw switch.
 */
export type AppVersionStatus =
  | 'DRAFT'
  | 'TESTING'
  | 'GATING'
  | 'GATE_PASSED'
  | 'GATE_LOG_ONLY'
  | 'GATE_BLOCKED'
  | 'RELEASED'
  | 'SUPERSEDED';

/**
 * t_kb_app_version.index_snapshots JSON element (M6-CONTRACTS.md section 0.10/2): one immutable
 * physical index built as a release-time snapshot, one row per (kb, engine) that version's
 * kb_refs touch. Snapshot indexes carry no alias and are looked up by physical name directly
 * (section 0.2), unlike the live index which is always addressed through its alias.
 */
export interface AppVersionIndexSnapshot {
  kb_id: string;
  engine: string;
  physical_index_name: string;
}

/**
 * Per-kb "可见集文档版本数" summary for the version-list expanded row (M6-CONTRACTS.md section 2).
 * ASSUMPTION: M6-CONTRACTS.md section 1 explicitly leaves this field's server-side name undecided
 * ("visible_version_kb_count/每库版本数摘要...字段命名可在实现时定版但须回报"); this web
 * implementation assumes an array of {kb_id, version_count} -- mirroring the shape of
 * index_snapshots/KbRef rather than exposing the raw visible_version_ids id-list, since the
 * expanded row only needs a count per kb, not the full document_version_id set -- named
 * visible_version_kb_count after the server section's own suggested name. Field name to be
 * reconciled once the server side lands.
 */
export interface VisibleVersionKbCount {
  kb_id: string;
  version_count: number;
}

/**
 * t_kb_app_version row (M4c-CONTRACTS.md section 1). gate_run_ids is modelled as an ordered pair
 * [candidate_run_id, baseline_run_id] -- ASSUMPTION: the contract only says "同语料双跑...候选配置
 * 与当前 RELEASED 配置各一轮" and names the column gate_run_ids JSON without giving element order;
 * this ordering is what GateCompareDrawer's labels assume and is not a guaranteed backend contract.
 */
export interface AppVersion {
  app_version_id: string;
  app_id: string;
  version: string;
  status: AppVersionStatus;
  config: AppVersionConfig;
  gate_dataset_id: string | null;
  gate_run_ids: string[] | null;
  gate_verdict: string | null;
  changelog: string | null;
  created_at: string;
  updated_at: string;
  /**
   * M6-CONTRACTS.md section 0.10/2: snapshot physical indexes created at release time, one entry
   * per (kb, engine). Absent/empty on versions that never went through the M6 release path --
   * either not yet RELEASED (still live-alias-served), or RELEASED before M6 shipped -- both
   * render as the "无索引快照，调用走实时索引" empty state rather than an error (section 0.4's
   * third branch: this is a historical/in-progress data shape, not a degraded condition).
   */
  index_snapshots?: AppVersionIndexSnapshot[];
  /** M6-CONTRACTS.md section 2; see VisibleVersionKbCount doc comment for the naming assumption. */
  visible_version_kb_count?: VisibleVersionKbCount[];
}

/**
 * POST /api/v1/apps/{id}/versions request body (M4c-CONTRACTS.md section 2: "从当前草稿配置建版").
 * ASSUMPTION: there is no separate persisted "draft config" resource on the app row in the
 * contract text; the web's 配置编辑 form holds the draft client-side and this call snapshots it
 * into a brand new DRAFT version, matching the section 4 UI description (配置编辑 + 版本列表 as two
 * panes of the same page rather than a bidirectional draft-sync API).
 */
export interface CreateAppVersionRequest extends AppVersionConfig {
  changelog?: string;
}

/**
 * PUT /api/v1/app-versions/{vid}/gate-dataset request body.
 * ASSUMPTION: section 4 lists "绑定门禁评测集选择" as its own version-list-row action distinct
 * from version creation, but section 2/3 do not name a binding endpoint; modelled as a dedicated
 * PUT so binding can happen (and be changed) any time before `release` is called, consistent with
 * "绑定 gate_dataset 时进入 GATING" being described as a release-time trigger, not a creation-time one.
 */
export interface BindGateDatasetRequest {
  dataset_id: string | null;
}

// ---------------------------------------------------------------------------
// Open API: external search/chat + admin chat preview (M4c-CONTRACTS.md section 3)
// ---------------------------------------------------------------------------

/**
 * POST /api/v1/knowledge/search request body (M4c-CONTRACTS.md section 3): app_id + optional
 * app_version (omitted = current RELEASED; explicit value targets TESTING for beta grayscale,
 * audited as target_stage=beta). Override whitelist is exactly top_n/score_threshold/
 * metadata_filter/max_content_length -- recall_top_k/fusion/rerank_enabled/rewrite_enabled live
 * only in the app version's frozen config and are intentionally absent from this request shape.
 */
export interface PublicSearchRequest {
  query: string;
  app_id: string;
  app_version?: string;
  messages?: ChatMessage[];
  max_content_length?: number;
  metadata_filter?: MetadataFilter;
  top_n?: number;
  score_threshold?: number | null;
  /**
   * M9-CONTRACTS.md section 0.6: optional base64-encoded image list (no URLs -- an external URL is
   * an SSRF surface), max 3 entries / 5MB decoded each / 10MB total; over the limit is INVALID_PARAM.
   * Each entry is the raw base64 payload only (no `data:image/...;base64,` prefix). Server converts
   * each image to text via VisionProvider and appends it to `query` before rewrite/retrieval;
   * zero-Key/no-vision-model/timeout/failure degrades to text-only search with
   * `degraded += image_understanding_unavailable` rather than failing the call outright.
   */
  images?: string[];
}

/** POST /api/v1/knowledge/chat request body (M4c-CONTRACTS.md section 3): same fields + stream. */
export interface ChatRequest extends PublicSearchRequest {
  /** Default false; true switches the response to the SSE event stream described below. */
  stream?: boolean;
}

/** Admin-authenticated chat preview body (see ChatRequest doc); app_id comes from the URL path instead. */
export type ChatPreviewRequest = Omit<ChatRequest, 'app_id'>;

/** Non-streaming POST /api/v1/knowledge/chat response (M4c-CONTRACTS.md section 3). */
export interface ChatResponse {
  answer: string;
  references: RetrievalNode[];
  request_id: string;
  degraded: string[];
  /**
   * M5-CONTRACTS.md section 2.2 ("applied 增 routed_kb_ids"). ASSUMPTION: M4c's ChatResponse has
   * no `applied` wrapper (unlike SearchResponse), so this sits as a top-level sibling of
   * `degraded` rather than nested under one; the SSE `done` event mirrors it for the same reason
   * (see ChatDoneEvent below).
   */
  routed_kb_ids: string[];
}

/**
 * SSE event payload shapes for stream=true chat (M4c-CONTRACTS.md section 3: "message_delta* ->
 * references -> done(含 request_id/degraded) -> 或 error"). ASSUMPTION: field names inside each
 * frame's `data:` JSON are not spelled out beyond the event-name sequence; modelled as the minimal
 * shape each event name implies.
 */
export interface ChatDeltaEvent {
  delta: string;
}
export interface ChatReferencesEvent {
  references: RetrievalNode[];
}
export interface ChatDoneEvent {
  request_id: string;
  degraded: string[];
  /** M5-CONTRACTS.md section 2.2; see ChatResponse.routed_kb_ids doc comment. */
  routed_kb_ids: string[];
}
export interface ChatErrorEvent {
  code: string;
  message: string;
}

// ---------------------------------------------------------------------------
// API Key management (M4c-CONTRACTS.md sections 1/3)
// ---------------------------------------------------------------------------

/**
 * t_kb_api_key.status. ASSUMPTION: the contract names the column ("status") but not its concrete
 * values; ENABLED/DISABLED mirrors IkDictStatus, the only other simple two-state status enum in
 * this codebase's contracts.
 */
export type ApiKeyStatus = 'ENABLED' | 'DISABLED';

/** t_kb_api_key row (M4c-CONTRACTS.md sections 1/3). app_scope null = every application. */
export interface ApiKey {
  key_id: string;
  name: string;
  /** Display-only prefix, e.g. "kb-sk-ab12"; the full secret is never returned again after creation/rotation. */
  prefix: string;
  /** Last 4 characters of the plaintext secret, shown alongside prefix per "列表(prefix+末4位)". */
  last4: string;
  status: ApiKeyStatus;
  qps_limit: number;
  app_scope: string[] | null;
  last_used_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface CreateApiKeyRequest {
  name: string;
  qps_limit: number;
  /** Omitted/null = all applications. */
  app_scope?: string[] | null;
}

/**
 * Creation/rotation response, the REAL server shape: the key row nested under `key` and the
 * plaintext as a top-level `api_key` sibling. The earlier flat `plain_key` extension rendered the
 * one-time secret modal empty and copied the literal string "undefined".
 */
export interface ApiKeyCreatedResponse {
  key: ApiKey;
  api_key: string;
}

/**
 * ASSUMPTION: section 3 lists "app_scope 配置" as its own management bullet separate from create;
 * modelled as a dedicated scope-update endpoint so an existing key's scope can be edited without a
 * full rotate (which would also invalidate the currently distributed secret).
 */
export interface UpdateApiKeyScopeRequest {
  app_scope: string[] | null;
}

// ---------------------------------------------------------------------------
// API audit log (M4c-CONTRACTS.md sections 1/3)
// ---------------------------------------------------------------------------

/** t_kb_api_audit_log.target_stage (M4c-CONTRACTS.md section 3). */
export type AuditTargetStage = 'release' | 'beta';

/**
 * t_kb_api_audit_log row (M4c-CONTRACTS.md section 1). ASSUMPTION: the contract does not name a
 * primary-key column for this table; audit_log_id follows the `xxx_id` convention used by every
 * other row type in this codebase (case_id, run_id, etc).
 */
export interface ApiAuditLogEntry {
  audit_log_id: string;
  key_id: string;
  app_version_id: string;
  target_stage: AuditTargetStage;
  /** Already desensitized + truncated to 200 chars server-side (M4c-CONTRACTS.md section 1). */
  query_digest: string;
  hit_doc_ids: string[];
  latency_ms: number;
  degraded: string[];
  request_id: string;
  created_at: string;
}

export interface AuditLogQueryParams {
  key_id?: string;
  /** ISO instant, inclusive lower bound. */
  from?: string;
  /** ISO instant, inclusive upper bound. */
  to?: string;
  target_stage?: AuditTargetStage;
  page?: number;
}

/**
 * GET /api/v1/api-audit-logs/stats response.
 * ASSUMPTION: section 4 only asks for "调用量简单统计" without a response schema; modelled as the
 * smallest useful call-volume summary (bucketed identically to the log query's filters) so the
 * audit tab can show a headline row above the filtered table without paging through every row
 * client-side.
 */
export interface AuditLogStats {
  total_calls: number;
  avg_latency_ms: number;
  degraded_calls: number;
  error_calls: number;
}

// ---------------------------------------------------------------------------
// GraphRAG (M7-CONTRACTS.md sections 0.1-0.10 / 2). Server delivered in parallel by another
// agent; every shape below is this web's best-effort assumption per section 0.10's blueprint,
// flagged for reconciliation once the real endpoints land.
// ---------------------------------------------------------------------------

/**
 * t_kb_task status for GRAPH_EXTRACT/GRAPH_CLEANUP rows (M7-CONTRACTS.md section 0.3).
 * ASSUMPTION: the contract says extraction progress is "入 t_kb_task 展示进度" but does not name
 * the status enum; mirrored from RunStatus (the only other server-driven async-job status enum
 * already in this codebase).
 */
export type GraphTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';

/**
 * t_kb_task.task_type values introduced by M7 (section 0.4: "TaskType 增 GRAPH_CLEANUP 或并入既有
 * CLEANUP（实现自选，报告申报）"). Both are modelled so the web can display whichever one the
 * server actually reports without a schema change later.
 */
export type GraphTaskType = 'GRAPH_EXTRACT' | 'GRAPH_CLEANUP';

/**
 * Latest graph task summary embedded in GraphSummary (section 0.10: "最近任务状态").
 * ASSUMPTION: field names mirror EvalRun's status/fail_reason/timestamps shape.
 * skipped_chunk_count surfaces the section 0.3 "非法 JSON/超长实体名/关系端点校验失败的分片跳过
 * 并计数（不 fail 整个任务）" behavior so the progress alert can show it without it reading as a
 * hard failure.
 */
export interface GraphTaskSummary {
  task_id: string;
  task_type: GraphTaskType;
  status: GraphTaskStatus;
  /** Chunks/batches the extraction pipeline skipped due to output validation (section 0.3), not a task failure. */
  skipped_chunk_count?: number;
  fail_reason: string | null;
  created_at: string;
  updated_at: string;
}

/**
 * GET /api/v1/kb/{kbId}/graph/summary response (section 0.10: "实体数/关系数/覆盖分片数/最近任务
 * 状态"). ASSUMPTION: graph_enabled is folded into this same response (in addition to
 * KnowledgeBase.graph_enabled) since the 知识图谱 tab's polling loop is the one place that needs
 * both the switch state and the extraction progress refreshed in lockstep.
 */
export interface GraphSummary {
  graph_enabled: boolean;
  entity_count: number;
  relation_count: number;
  covered_chunk_count: number;
  latest_task: GraphTaskSummary | null;
}

/** PUT /api/v1/kb/{kbId}/graph/config request body (section 0.10). */
export interface UpdateGraphConfigRequest {
  enabled: boolean;
}

/**
 * POST /api/v1/kb/{kbId}/graph/extract response (section 0.10: "手动触发全量重抽").
 * ASSUMPTION: mirrors ActivateVersionResponse's task_id-returning shape for an async server job;
 * the web does not strictly need task_id back (it re-polls /graph/summary for latest_task) but
 * keeping it lets the trigger button's success message reference the concrete task.
 */
export interface TriggerGraphExtractResponse {
  task_id: string;
}

/**
 * One (:Entity)-[:REL]->(:Entity) outgoing edge, embedded on GraphEntity below.
 * ASSUMPTION: section 0.10 lists exactly 5 graph endpoints and none of them is a standalone
 * "relations" listing, yet section 2's "简版可视化：以当前实体列表前 N 个实体的关系做...布局"
 * requires edges between the top-N entities. Rather than inventing a 6th endpoint, `relations` is
 * assumed bundled onto each GraphEntity row the entities endpoint already returns -- the server
 * already has the entity's outgoing REL edges in hand when it resolves source_chunk_count from
 * the same Neo4j node. Flagged for reconciliation with the server agent's actual response shape.
 */
export interface GraphEntityRelation {
  target: string;
  type: string;
}

/**
 * GET /api/v1/kb/{kbId}/graph/entities item (section 0.10: "实体列表带来源分片数").
 * `name` doubles as the row identifier -- entities have no dedicated id column per the graph
 * model in section 0.2 (MERGE'd by (kb_id, name)), mirroring how IkDictEntry uses `word`.
 */
export interface GraphEntity {
  name: string;
  /** Freeform LLM-extracted type label (e.g. "人物"/"组织"/"地点"), not a closed enum -- never render through metaOf/a fixed color table. */
  type: string;
  source_chunk_count: number;
  /** See GraphEntityRelation doc comment for the "no dedicated relations endpoint" assumption. */
  relations: GraphEntityRelation[];
}

export interface ListGraphEntitiesParams {
  query?: string;
  page?: number;
  /** Page size override; the visualization pulls one larger page (default 50) instead of the entity-list tab's own pagination size. */
  size?: number;
}

/**
 * GET /api/v1/kb/{kbId}/graph/entities/{entityName}/chunks item (section 0.10: "下钻来源分片
 * （含所属文档版本）...复用 RetrievalNode 结构或简化行,报告申报"). Modelled as a simplified row
 * rather than the full RetrievalNode (no score/retrieval_source -- this is a drill-down listing,
 * not a ranked search result), denormalizing doc_file_name/document_version_label so the drawer's
 * "含所属文档版本" requirement doesn't need N further per-row lookups against /documents or
 * /versions.
 */
export interface GraphEntitySourceChunk {
  chunk_id: string;
  doc_id: string;
  doc_file_name: string;
  document_version_id: string;
  /** Display label, e.g. "v3" (DocumentVersion.version) -- not the raw document_version_id. */
  document_version_label: string;
  content: string;
  enabled: boolean;
}

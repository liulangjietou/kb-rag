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

/** t_kb_document.process_status, see M1-CONTRACTS.md section 2. */
export type ProcessStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'PARSE_FAILED'
  | 'PARSED'
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

/** RetrievalNode, see M1-CONTRACTS.md section 5, extended by M2-CONTRACTS.md section 1.5. */
export interface RetrievalNode {
  doc_id: string;
  document_version_id: string;
  chunk_id: string;
  chunk_type: string;
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

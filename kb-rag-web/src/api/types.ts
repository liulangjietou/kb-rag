// Shared type definitions mirroring kb-rag-deploy/docs/M1-CONTRACTS.md section 5 and
// kb-rag-deploy/docs/M2-CONTRACTS.md sections 1/3/4 (REST API contract).
// Author: owlzhangfq@gmail.com
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

/**
 * Which credential store verifies the login. `SSO` binds against the corporate directory, `LOCAL`
 * against the password hash stored in t_kb_admin_user. Absent means LOCAL server-side; the login page
 * sends it explicitly so the audit row records which door was used.
 */
export type LoginMode = 'LOCAL' | 'SSO';

export interface LoginRequest {
  username: string;
  password: string;
  mode?: LoginMode;
}

export interface LoginResponse {
  token: string;
  must_change_password: boolean;
}

/** GET /auth/sso-available: whether this deployment has a directory configured at all. */
export interface SsoAvailability {
  sso_available: boolean;
}

/**
 * GET /auth/sso/providers (M16-CONTRACTS.md section 5): which browser SSO protocols this
 * deployment has configured. Each true flag renders one redirect button on the login page; the
 * button sends the browser to /api/v1/auth/{protocol}/login and the callback lands the token in
 * the /login URL fragment.
 */
export interface SsoProviders {
  oidc: boolean;
  saml: boolean;
  cas: boolean;
}

export interface ChangePasswordRequest {
  old_password: string;
  new_password: string;
}

/** Where an account came from; LDAP accounts have no local password to rotate or reset. */
export type UserSource = 'LOCAL' | 'LDAP';

export type UserStatus = 'ENABLED' | 'DISABLED';

/**
 * GET /auth/me. Carries the flattened permission codes of the session so the console can hide what the
 * caller cannot use. Hiding is presentation only -- every code is checked again server-side, so a stale
 * or tampered copy of this payload buys nothing.
 */
export interface CurrentUser {
  username: string;
  display_name: string | null;
  source: UserSource | null;
  must_change_password: boolean;
  last_login_at: string | null;
  roles: string[];
  permissions: string[];
  /** True when the account sees every knowledge base, present and future. */
  kb_scope_all: boolean;
  /** Knowledge bases in scope; meaningful only while kb_scope_all is false. */
  kb_ids: string[];
}

// ---------------------------------------------------------------------------
// Users and roles (RBAC administration)
// ---------------------------------------------------------------------------

/** One row of GET /users. role_names is filled on the list, role_ids on the single-account read. */
export interface UserSummary {
  user_id: string;
  /** Owning tenant (M16). Every account belongs to exactly one; the default tenant before a move. */
  tenant_id: string;
  username: string;
  display_name: string | null;
  email: string | null;
  source: UserSource;
  status: UserStatus;
  must_change_password: boolean;
  last_login_at: string | null;
  created_at: string | null;
  role_ids: string[] | null;
  role_names: string[] | null;
}

export interface CreateUserRequest {
  username: string;
  display_name?: string;
  email?: string;
  password: string;
  role_ids: string[];
}

export interface UpdateUserRequest {
  display_name?: string;
  email?: string;
}

/**
 * A role and everything it grants: the function level permission codes plus the knowledge base data
 * scope. kb_scope_all beats kb_ids -- when it is true the id list is not consulted at all.
 */
export interface RoleSummary {
  role_id: string;
  /**
   * 所属租户。内置角色每个租户各有一份、编码相同，平台运维跨租户读这张表时靠它区分同名行。
   */
  tenant_id: string;
  code: string;
  name: string;
  description: string | null;
  /** Built-in roles ship with the product; their code cannot be edited and they cannot be deleted. */
  builtin: boolean;
  kb_scope_all: boolean;
  kb_ids: string[];
  permission_codes: string[];
}

export interface SaveRoleRequest {
  /** Ignored on update: a role's code is its stable identity in scripts and seeds. */
  code?: string;
  name: string;
  description?: string;
  kb_scope_all: boolean;
  kb_ids: string[];
  permission_codes: string[];
}

/** One entry of GET /roles/permissions, the catalogue the role editor renders grouped by module. */
export interface PermissionCatalogueItem {
  code: string;
  name: string;
  module: string;
  module_name: string;
}

// ---------------------------------------------------------------------------
// Knowledge base
// ---------------------------------------------------------------------------

/**
 * index_config.split_strategy: the splitter the indexing pipeline routes to (M14 contract
 * section 4). Five strategies exist server-side, one per TextSplitter STRATEGY_CODE:
 * `separator` cuts on a literal or regex block delimiter, `heading` cuts on a Markdown heading
 * depth, `page` keeps one chunk per source page. Parent/child is its own switch rather than a
 * sixth strategy, but it is not orthogonal: the two level splitter composes `fixed_length` with
 * itself instead of running the configured strategy, so the server rejects every other pairing.
 *
 * `llm_semantic` requires a configured chat model; saving it without one is rejected server-side
 * with INVALID_PARAM ("the LLM semantic split strategy requires a configured chat model").
 */
export type SplitStrategy = 'fixed_length' | 'llm_semantic' | 'separator' | 'heading' | 'page';

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
 * index_config.chat_aggregation (M3-CONTRACTS.md section 3.5): sequential windowing applied when
 * slicing an imported chat session into chunk_type=chat_log chunks. Nesting verified against
 * KbIndexConfig.chatAggregation (server).
 */
export interface ChatAggregationConfig {
  window_minutes: number;
  max_messages: number;
  /**
   * M8-CONTRACTS.md section 0.5: how many trailing messages the next window repeats. 0 = the
   * pre-M8 straight-cut behavior. Server bound: window_overlap * 2 < max_messages, else
   * INVALID_PARAM. MUST be sent on every index-config PUT -- the server replaces the whole
   * chat_aggregation object, so omitting this silently resets a configured overlap back to 0.
   */
  window_overlap: number;
}

/**
 * M14 contract section 3.1: one metadata_rules entry (server MetadataRule). `constant` stamps a
 * fixed value on every chunk, `regex` captures the first group of a pattern out of the chunk text,
 * `keyword_match` records which words of a vocabulary the chunk contains. key must match
 * ^[a-z][a-z0-9_]{1,31}$; value belongs to `constant`, pattern to `regex`, keywords to
 * `keyword_match` -- the other two stay absent per type.
 */
export type MetadataRuleType = 'constant' | 'regex' | 'keyword_match';

/** One operator declared metadata extraction rule of index_config.metadata_rules. */
export interface MetadataRule {
  key: string;
  type: MetadataRuleType;
  /** `constant` only: the fixed value stamped on every chunk. */
  value?: string;
  /** `regex` only: pattern source, at most 64 chars server-side. */
  pattern?: string;
  /** `keyword_match` only: vocabulary, at most 50 words of at most 32 chars each. */
  keywords?: string[];
}

/**
 * t_kb_knowledge_base.index_config JSON (M1-CONTRACTS.md section 2). Field names verified against
 * the server's KbIndexConfig (2026-07-27).
 */
export interface IndexConfig {
  /**
   * Which splitter the indexing pipeline routes to. Omitting the key on a PUT keeps the stored
   * value (UpdateIndexConfigRequest.toIndexConfig falls back to current); an unknown code is
   * rejected with INVALID_PARAM rather than silently ignored.
   */
  split_strategy?: SplitStrategy;
  /**
   * M14 contract section 4: block delimiter of the `separator` strategy. null/absent lets the
   * strategy fall back to its own default; ignored by every other strategy.
   */
  split_separator?: string;
  /** M14 contract section 4: whether split_separator is a regular expression (`separator` strategy). */
  split_separator_is_regex?: boolean;
  /**
   * M14 contract section 4: Markdown heading depth of the `heading` strategy. 0/absent lets the
   * strategy fall back to its own default depth; ignored by every other strategy.
   */
  split_heading_level?: number;
  /**
   * M14 contract section 3.1: operator declared metadata extraction rules, at most 10. Empty/absent
   * = current behaviour (no operator metadata stamped). Participates in the chunk fingerprint, so
   * editing marks the affected documents config_stale.
   */
  metadata_rules?: MetadataRule[];
  /**
   * M14 contract section 6.2: turns on the multimodal vector route for this KB. Default false so
   * shipping the feature changes nothing. Participates in the chunk fingerprint, so flipping marks
   * the affected documents config_stale.
   */
  multimodal_enabled?: boolean;
  /** Embedding model the current index was built with; server-assigned, never sent back on a PUT. */
  embedding_model?: string;
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

/**
 * PUT /api/v1/kb/{kbId}/index-config request body (M2-CONTRACTS.md section 4). embedding_model is
 * server-owned and has no counterpart on UpdateIndexConfigRequest, so it is never sent back.
 */
export type UpdateIndexConfigRequest = Omit<IndexConfig, 'embedding_model'>;

/** KnowledgeBaseResponse (server). Note there is no updated_at -- only created_at is exposed. */
export interface KnowledgeBase {
  kb_id: string;
  name: string;
  description: string | null;
  /** Returned inline by GET /kb and GET /kb/{kbId} so the edit drawer needs no second fetch. */
  index_config: IndexConfig | null;
  current_config_fingerprint: string | null;
  created_at: string;
  /**
   * M7-CONTRACTS.md section 0.1/0.10: knowledge-base-level GraphRAG switch, stored in the server's
   * KnowledgeBase.retrievalConfig JSON (default false). Always present on the response (the server
   * emits a primitive boolean); optional here only so pre-M7 fixtures still type-check.
   */
  graph_enabled?: boolean;
  /**
   * M11-CONTRACTS.md section 2.2: when true, new documents of this KB start as DRAFT and must pass
   * review before they become retrievable. Only future uploads read the switch. Optional so pre-M11
   * fixtures still type-check; the server always emits it.
   */
  review_required?: boolean;
}

export interface CreateKbRequest {
  name: string;
  description?: string;
}

/** PUT /api/v1/kb/{kbId} request body: rename and re-describe, nothing else. */
export interface UpdateKbRequest {
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

/**
 * t_kb_document.publish_status (M11-CONTRACTS.md section 2.1): the editorial state, orthogonal to
 * process_status. Transitions: DRAFT | REJECTED -> PENDING_REVIEW -> PUBLISHED | REJECTED, with
 * PUBLISHED terminal (taking content offline is an expiry or a trash operation, not a rollback).
 */
export type PublishStatus = 'DRAFT' | 'PENDING_REVIEW' | 'PUBLISHED' | 'REJECTED';

/** Tag color + label per publish status, shared by the document list and the trash tab. */
export const PUBLISH_STATUS_META: Record<PublishStatus, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  PENDING_REVIEW: { color: 'processing', label: '待审核' },
  PUBLISHED: { color: 'success', label: '已发布' },
  REJECTED: { color: 'error', label: '已驳回' },
};

/**
 * DocumentResponse (server). The four upload-only fields at the bottom are populated exclusively by
 * POST /kb/{kbId}/documents (UploadOutcome) and are absent from the list/detail responses.
 * No updated_at is exposed.
 */
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
  /** M11: editorial state; the server answers PUBLISHED for every document that predates M11. */
  publish_status: PublishStatus;
  /** M11: latest rejection reason, null unless publish_status is REJECTED. */
  review_note: string | null;
  /** M11: ISO lower bound of the validity window, null = no lower bound. */
  effective_at: string | null;
  /** M11: ISO upper bound of the validity window, null = no upper bound. */
  expires_at: string | null;
  /** M11: ISO instant the document entered the recycle bin, null outside it. */
  trashed_at: string | null;
  /** M16: content readable only by the granted roles; the row itself always shows in the list. */
  restricted: boolean;
  created_at: string;
  /** Upload only: id of the document version this upload created. */
  version_id?: string;
  /** Upload only: label of that version, e.g. "v2" (M4a-CONTRACTS.md section 1.1's three branches). */
  version?: string;
  /** Upload only: true when content_hash matched an existing version and nothing was re-parsed. */
  duplicated?: boolean;
  /** Upload only: set when the uploaded file duplicates a *different* document in the same kb. */
  duplicate_of_doc_id?: string;
}

export type EmbeddingStatus = 'PENDING' | 'DONE' | 'FAILED' | 'SKIPPED';

/**
 * ChunkResponse (server). Deliberately carries neither kb_id nor doc_id -- both are already known
 * from the route the caller used to fetch the chunk.
 */
export interface KbChunk {
  chunk_id: string;
  document_version_id: string;
  content: string;
  chunk_type: ChunkType;
  /** SHA-256 of the chunk text; the exact-match key annotation inheritance uses across versions. */
  chunk_text_hash: string;
  parent_id: string | null;
  seq: number;
  enabled: boolean;
  /**
   * M4a-CONTRACTS.md section 2.2: ids of this chunk's disabled child chunks, computed server-side
   * across the whole version (not just the current page). Always present, `[]` when there are none
   * or when the chunk is not a parent.
   */
  disabled_child_ids: string[];
  embedding_status: EmbeddingStatus;
  metadata: Record<string, unknown> | null;
}

/** POST /api/v1/kb/{kbId}/rebuild request body (M2-CONTRACTS.md section 4). */
export interface RebuildRequest {
  doc_ids?: string[];
}

/**
 * GET /api/v1/kb/{kbId}/rebuild-status response：整库口径的配置追平状态。
 *
 * 三个计数都是服务端现算的，与文档列表的分页无关——待重建的文档落在第几页不影响它是不是活儿。
 */
export interface RebuildStatus {
  /** 仍需按新配置重建的文档数，归零即全部追平。 */
  stale_count: number;
  /** 其中正在跑重建管线的文档数。 */
  in_progress_count: number;
  /** 其中重建失败、需要人工介入的文档数。 */
  failed_count: number;
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
  /**
   * M14 contract section 3.3: equality predicates on operator extracted metadata, keyed by the raw
   * rule key (each key must match ^[a-z][a-z0-9_]{1,31}$, else the search is rejected INVALID_PARAM).
   */
  custom?: Record<string, string>;
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
 * section 1.5). Key names verified against RetrievalService's META_* constants: every score key
 * below is written through putIfPresent, i.e. it is absent (never null) when the route that would
 * produce it did not contribute to this candidate.
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
  /** 1-based rank this candidate held on the vector route before fusion. */
  vector_rank?: number;
  /** 1-based rank this candidate held on the BM25 route before fusion. */
  bm25_rank?: number;
  fused_score?: number;
  rerank_score?: number;
  /** Chunk sequence number within its document version. */
  chunk_seq?: number;
  /** Present only in parent/child mode: ids of the child chunks merged into this parent node. */
  child_ids?: string[];
  /**
   * M4a-CONTRACTS.md section 2.2: ids of this parent's disabled child chunks. On a SEARCH node the
   * flag lives here in metadata; on a GET /documents/{docId}/chunks row it is a TOP-LEVEL
   * KbChunk.disabled_child_ids field instead. Two endpoints, two placements -- do not mix them up.
   */
  disabled_child_ids?: string[];
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

/**
 * M14 contract section 5: rerank ordering mode. `semantic` is the pre-M14 behaviour (order by the
 * rerank model score alone); `hybrid` blends the rerank score with the fusion score by
 * rerank_w_semantic. Default `semantic` when absent.
 */
export type RerankMode = 'semantic' | 'hybrid';

export interface SearchRequest {
  query: string;
  /**
   * M14 contract section 7: base64 encoded images attached to the query for image-to-image /
   * multimodal recall. Absent/empty = text-only search (pre-M14 behaviour). Bounded and validated
   * server-side by the image gate; images that cannot be embedded are dropped with a degraded marker.
   */
  images?: string[];
  recall_top_k: number;
  top_n: number;
  /** 0.01-1.0, omit/null = no filtering (M2-CONTRACTS.md section 1.3). */
  score_threshold?: number | null;
  fusion?: FusionConfig;
  /** Default true when a rerank model is configured (M2-CONTRACTS.md section 1.5). */
  rerank_enabled?: boolean;
  /** M14 contract section 5: rerank ordering mode, default `semantic`. */
  rerank_mode?: RerankMode;
  /**
   * M14 contract section 5: semantic weight of the `hybrid` rerank mode, within [0,1] (the fusion
   * weight is its complement). Only meaningful when rerank_mode = 'hybrid'.
   */
  rerank_w_semantic?: number;
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

/**
 * POST /api/v1/knowledge/search response (KnowledgeSearchResponse): the admin SearchResponse plus
 * which application version actually served the call. Only the external, API-Key-gated endpoint
 * reports these two -- the admin debug search has no app version behind it.
 */
export interface PublicSearchResponse extends SearchResponse {
  /** Version label that served this call, e.g. "V2.0". */
  app_version: string;
  /** 'release' when the current RELEASED version served it, 'beta' when an explicit TESTING one did. */
  target_stage: AuditTargetStage;
}

// ---------------------------------------------------------------------------
// System / model status
// ---------------------------------------------------------------------------

/** Per-model-kind configuration snapshot, used by the M2 settings page's three status cards. */
/**
 * GET /api/v1/system/model-status response (M2-CONTRACTS.md section 5). Flat, one triple per model
 * kind; verified field-for-field against the server's ModelStatusResponse.
 */
export interface ModelStatus {
  embedding_configured: boolean;
  provider: string | null;
  model: string | null;
  /** Embedding vector width the configured provider declares; 0 when no embedding model is set. */
  dimension: number;
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
  /**
   * M14 contract section 6.2: whether a multimodal embedding provider is configured. When false the
   * console greys out the KB-level "多模态整页索引" switch (the switch still stores, but indexing is
   * skipped, the same zero-key tolerance the text vector route has). Optional so pre-M14 fixtures
   * still type-check; the server always emits it.
   */
  multimodal_configured?: boolean;
  multimodal_provider?: string | null;
  multimodal_model?: string | null;
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
 * t_kb_ik_dict row. The table has no business `xxx_id` column, so `word` (the unique key) is the
 * entry identifier the delete/status routes take -- confirmed by IkDictController's `/{word}`
 * path templates.
 */
export interface IkDictEntry {
  word: string;
  dict_type: IkDictType;
  status: IkDictStatus;
  /** Free-text note, max 512 chars server-side. */
  remark: string | null;
  created_at: string;
  updated_at: string;
}

export interface CreateIkDictEntryRequest {
  word: string;
  dict_type: IkDictType;
  remark?: string;
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
  /** Source page, absent for images that carry no page anchor. */
  page_no?: number;
  /** Image origin, e.g. an embedded figure vs a rendered scan page. */
  kind?: string;
  text_proxy: string;
  /** VLM proxy-text state; a failed/skipped image still lists here with an empty text_proxy. */
  status?: string;
}

/** GET /api/v1/documents/{docId}/preview response. */
export interface DocumentPreview {
  doc_id: string;
  process_status: ProcessStatus;
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

/**
 * POST /api/v1/kb/{kbId}/documents/batch-delete | batch-reindex 请求体。
 * doc_ids 必填：这两个操作由列表勾选驱动，服务端不会把空列表当成"整库"。
 */
export interface DocumentBatchRequest {
  doc_ids: string[];
}

/** POST /api/v1/kb/{kbId}/documents/batch-delete 响应：真正移入回收站的文档，已在回收站的会被跳过。 */
export interface BatchDeleteDocumentsResult {
  deleted_doc_ids: string[];
}

/** POST /api/v1/kb/{kbId}/documents/batch-reindex 响应：真正提交重建的文档，无版本的会被跳过。 */
export interface BatchReindexDocumentsResult {
  reindexed_doc_ids: string[];
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
 * Verified field-for-field against the server's SourceMappingResponse: mapping_id as the id column
 * plus both timestamps -- one of the few row types here that really does expose updated_at.
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
 * Built-ins are read-only per section 0.7 ("不可改"), so the web only ever calls this on a custom
 * row. Shape verified against the server's SourceMappingRequest: the update body is the same
 * full-row shape as create, there is no narrower partial-update schema.
 */
export type UpdateSourceMappingRequest = CreateSourceMappingRequest;

/**
 * POST /api/v1/source-mappings/{mappingId}/copy request body -- "复制为自定义" action on a
 * built-in row (section 0.7).
 * Verified: the server does expose this as its own action route
 * (SourceMappingController#copy + SourceMappingCopyRequest), taking an optional display name.
 */
export interface CopySourceMappingRequest {
  /** Optional display name for the copy; omitted lets the server derive one (e.g. "{name} 副本"). */
  name?: string;
}

// ---------------------------------------------------------------------------
// Chat log import (M3-CONTRACTS.md section 3.5, mapping picker extended by M8-CONTRACTS.md section 0.7)
// ---------------------------------------------------------------------------

export type ChatImportAction = 'CREATE' | 'NEW_VERSION';

/** Epoch-millis [from, to] pair, matching the server's ChatImportPreviewResponse.TimeRange. */
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
 * POST /api/v1/kb/{kbId}/chat-imports response. The upload_token sits alongside `sessions` and is
 * what the confirm step replays against the MinIO staging area (30 min TTL).
 */
export interface ChatImportPreviewResponse {
  upload_token: string;
  sessions: ChatImportSessionPreview[];
  /**
   * M3-CONTRACTS.md section 3.5: messages the parser dropped, keyed by reason (e.g. voice/video
   * rows that carry no text) with the count per reason. Absent when nothing was skipped.
   */
  skipped?: Record<string, number>;
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
 * POST /api/v1/system/demo/import response -- a bare {kb_id}, verified against SystemController
 * (which returns exactly `Map.of("kb_id", ...)` on both the first call and idempotent repeats).
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
 * M6-CONTRACTS.md section 0.8/2 with the AppVersionPinChecker's pin state. pinned/pinned_by
 * verified against DocumentVersionResponse (server).
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
 * POST /api/v1/documents/{docId}/versions/{versionId}/activate response. Both branches share one
 * shape: REBUILD returns a task_id to poll, INSTANT returns task_id null (the server marks the
 * record @JsonInclude(ALWAYS) precisely so the key survives the global non-null serialization).
 */
export interface ActivateVersionResponse {
  task_id: string | null;
  /** Which branch actually ran; mirrors ActivateImpact.rollback_mode from the pre-flight check. */
  rollback_mode: RollbackMode;
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
  /** Label of the version the annotation was made on, e.g. "v2" -- not the raw document_version_id. */
  version: string;
  chunk_id: string;
  annotation_type: AnnotationType;
  payload: AnnotationPayload;
  /** Server-truncated excerpt of the annotated chunk's text ("原文摘录"). */
  excerpt: string | null;
  chunk_text_hash: string;
  inherit_status: InheritStatus;
  operator: string;
  created_at: string;
  /**
   * M9-CONTRACTS.md section 0.5. Always present -- PendingAnnotationResponse maps a null suggestion
   * list to `[]`, so the workbench renders "无相似候选" off an empty array, never a missing key.
   */
  suggestions: AnnotationMigrationSuggestion[];
}

/**
 * POST /api/v1/annotations/{annotationId}/migrate response (M9-CONTRACTS.md section 0.5).
 * `already_migrated` is how the idempotent repeat call reports itself: the row was already applied
 * to this target, so nothing changed and changed_chunk_ids comes back empty.
 */
export interface AnnotationMigrationResult {
  annotation_id: string;
  target_chunk_id: string;
  annotation_type: AnnotationType;
  inherit_status: InheritStatus;
  changed_chunk_ids: string[];
  already_migrated: boolean;
}

/**
 * POST /api/v1/annotations/{annotationId}/migrate request body (M9-CONTRACTS.md section 0.5):
 * applies the pending row's edit/disable semantics onto `target_chunk_id` in the newly active
 * version and marks the original pending row processed; idempotent when repeated with the same
 * target. See AnnotationMigrationResult for what comes back.
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

/**
 * t_kb_eval_case.source (M4b-CONTRACTS.md section 1), extended by M10-CONTRACTS.md section 2.1:
 * FEEDBACK marks cases born from converting a GOOD retrieval feedback in the feedback management tab.
 */
export type CaseSource = 'MANUAL' | 'DEBUG_PAGE' | 'IMPORTED' | 'FEEDBACK';

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
 * Verified against EvalDatasetResponse.LastRunSummary: {run_id, status, mode, finished_at}, marked
 * @JsonInclude(ALWAYS) server-side so the key is present (null) even on a dataset that never ran.
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
 * Verified against StaleCaseResponse.CandidateView: {doc_id, chunk_id, span, overlap_ratio}.
 */
export interface StaleEvidenceCandidate {
  doc_id: string;
  chunk_id: string;
  span: string;
  overlap_ratio: number;
}

/**
 * Pairs one stale (no-longer-matching) evidence of a case with its Top-3 replacement candidates;
 * verified against StaleCaseResponse.StaleEvidenceView.
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
 * Verified against ImportDemoEvalDatasetResponse: the created/existing dataset id plus an import
 * count and a skipped-case reason list ({case_index, reason}).
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
 * Verified against EvalRunEstimateResponse: the four call-count keys in exactly that order.
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

/** t_kb_app row (M4c-CONTRACTS.md section 1), plus the denormalized pointer to its live version. */
export interface KbApp {
  app_id: string;
  name: string;
  description: string | null;
  /** Version label of this app's current RELEASED version, absent when nothing is released yet. */
  released_version?: string;
  /** app_version_id of that same version; absent for the same reason. */
  released_version_id?: string;
  created_at: string;
  updated_at: string;
}

export interface CreateAppRequest {
  name: string;
  description?: string;
}

/**
 * Verified: AppController exposes PUT /apps/{appId} taking UpdateAppRequest{name, description},
 * the same shape as create.
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

/**
 * t_kb_app_version.config.gate (server AppConfigSnapshot.GateThresholds): the per-version release
 * gate floor. Absent/both-null means the version falls back to the first-release baseline rule
 * instead of an explicit threshold (M4c-CONTRACTS.md section 2).
 */
export interface AppGateThresholds {
  min_hit_rate?: number | null;
  min_recall?: number | null;
}

export interface AppVersionConfig {
  /** 1..15 entries (M5-CONTRACTS.md section 1), replacing M4c's single kb_id. */
  kb_refs: KbRef[];
  retrieval: AppRetrievalConfig;
  prompt: AppPromptConfig;
  /** Absent on pre-M5 snapshots; read as `config.routing?.enabled ?? false` / `?.prompt ?? null`. */
  routing?: AppRoutingConfig;
  /**
   * M4c-CONTRACTS.md section 1: the chat model frozen into this version's snapshot (resolved
   * through ChatProviderFactory at call time). null/absent = the server default chat model.
   * This web has no picker for it yet, but the value MUST be carried through when a new version is
   * built from an existing one -- the server snapshots exactly what the request holds, so dropping
   * the key silently reverts the app to the default model.
   */
  chat_model?: string | null;
  /** Release gate floor; carried through for the same reason as chat_model above. */
  gate?: AppGateThresholds | null;
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
 * The name M6-CONTRACTS.md section 1 left open was settled server-side as visible_version_kb_count
 * carrying {kb_id, version_count} rows (VisibleVersionCountResponse) -- verified, matches.
 */
export interface VisibleVersionKbCount {
  kb_id: string;
  version_count: number;
}

/**
 * Structured gate report frozen on the version at gate time (server GateReport). Rendered by
 * GateCompareDrawer alongside the two runs' metrics.
 */
export interface AppGateReport {
  verdict: string;
  reason: string;
  message: string;
  candidate: { hit_rate: number; recall: number } | null;
  baseline: { hit_rate: number; recall: number } | null;
  effective_cases: number;
  total_cases: number;
  stale_cases: number;
  stale_ratio: number;
  degraded_cases: number;
  /** Tolerance the double-run comparison allowed, max(2pp, 1/N). */
  epsilon: number;
  hit_rate_delta: number | null;
  recall_delta: number | null;
  candidate_run_id: string;
  baseline_run_id: string | null;
  evaluated_at: string;
  case_ids: string[];
}

/**
 * t_kb_app_version row (M4c-CONTRACTS.md section 1). gate_run_ids is an ordered pair
 * [candidate_run_id, baseline_run_id] -- VERIFIED against ReleaseGateService (server), which
 * appends the candidate run first and the baseline second, omitting the second element entirely
 * on a first release with no RELEASED predecessor to compare against.
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
  /** Classified gate reason code, e.g. why a run was blocked or only logged. */
  gate_reason?: string;
  /** Human-readable rendering of gate_reason, ready to display as-is. */
  gate_reason_message?: string;
  gate_report?: AppGateReport;
  /** True when a GATE_LOG_ONLY version was pushed through with force=true ("留痕放行"). */
  force_released: boolean;
  /** Operator who forced it; present only alongside force_released=true. */
  force_operator?: string;
  changelog: string | null;
  released_at?: string;
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
  /** M6-CONTRACTS.md section 2; `[]` on versions with no frozen visible set. */
  visible_version_kb_count?: VisibleVersionKbCount[];
}

/**
 * POST /api/v1/apps/{id}/versions request body (M4c-CONTRACTS.md section 2: "从当前草稿配置建版").
 * There is no persisted server-side "draft config" resource: the 配置编辑 form holds the draft
 * client-side and this call snapshots it into a brand new DRAFT version.
 *
 * The server maps this body through AppVersionConfigRequest#toSnapshot, which writes EVERY branch
 * of the snapshot from what the request carries. A key the request omits is therefore stored as
 * its default, not inherited from the version the form was pre-filled from -- which is why
 * chat_model and gate must be round-tripped even though this web has no editor for them.
 */
export interface CreateAppVersionRequest extends AppVersionConfig {
  changelog?: string;
}

/**
 * PUT /api/v1/app-versions/{vid}/gate-dataset request body. Verified against
 * AppVersionController#gateDataset + GateDatasetRequest: binding is its own route and can be
 * changed any time before `release` is called.
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
   * M5-CONTRACTS.md section 2.2: a TOP-LEVEL sibling of `degraded`, not nested under an `applied`
   * wrapper (ChatResponse has none, unlike SearchResponse) -- this is the M5 主会话定版 and the SSE
   * `done` event mirrors it (see ChatDoneEvent below).
   */
  routed_kb_ids: string[];
  /** Version label that served this call; see PublicSearchResponse. */
  app_version?: string;
  /** 'release' | 'beta'; see PublicSearchResponse. */
  target_stage?: AuditTargetStage;
}

/**
 * SSE event payload shapes for stream=true chat (M4c-CONTRACTS.md section 3: "message_delta* ->
 * references -> done(含 request_id/degraded) -> 或 error"). Verified against the server's
 * SseChatStreamListener: event names and per-frame field names match exactly.
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

/** t_kb_api_key.status; ENABLED/DISABLED, verified against the server's ApiKeyStatus enum. */
export type ApiKeyStatus = 'ENABLED' | 'DISABLED';

/**
 * t_kb_api_key row (M4c-CONTRACTS.md sections 1/3).
 *
 * `app_scope` is ALWAYS an array -- ApiKeyService.scopeOf maps a null/blank column to `[]`, and an
 * EMPTY ARRAY means "authorises every application", the same thing sending null on the write side
 * means. Never test it with `=== null`.
 */
export interface ApiKey {
  key_id: string;
  name: string;
  /**
   * The masked display form of the secret, already complete: the server stores it pre-elided as
   * "kb-sk-58e086…5a4a" (head + last 4). There is no separate last-4 field -- t_kb_api_key keeps
   * only the hash, so the plaintext tail cannot be re-derived. Render this value verbatim; do not
   * append a mask of your own.
   */
  prefix: string;
  status: ApiKeyStatus;
  qps_limit: number;
  app_scope: string[];
  last_used_at: string | null;
  created_at: string;
}

export interface CreateApiKeyRequest {
  name: string;
  qps_limit: number;
  /** Omitted/null/empty = all applications. */
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
 * PUT /api/v1/api-keys/{keyId}/scope body. Verified against ApiKeyController#updateScope: editing
 * the scope does not rotate the secret. null/empty both mean "every application".
 */
export interface UpdateApiKeyScopeRequest {
  app_scope: string[] | null;
}

// ---------------------------------------------------------------------------
// API audit log (M4c-CONTRACTS.md sections 1/3)
// ---------------------------------------------------------------------------

/** t_kb_api_audit_log.target_stage (M4c-CONTRACTS.md section 3). */
export type AuditTargetStage = 'release' | 'beta';

/** t_kb_api_audit_log row (M4c-CONTRACTS.md section 1), verified against ApiAuditLogResponse. */
export interface ApiAuditLogEntry {
  audit_log_id: string;
  key_id: string;
  app_id: string;
  app_version_id: string;
  target_stage: AuditTargetStage;
  /** Which endpoint was called, e.g. "/api/v1/knowledge/search". */
  endpoint: string;
  /** Already desensitized + truncated to 200 chars server-side (M4c-CONTRACTS.md section 1). */
  query_digest: string;
  hit_doc_ids: string[];
  latency_ms: number;
  degraded: string[];
  /** Which of the four whitelisted override params the caller actually presented. */
  override_keys: string[];
  /** Business error code when the call failed; absent on success (401 is not audited, 429 is). */
  error_code?: string;
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
 * GET /api/v1/api-audit-logs/stats response, verified against ApiAuditStatsResponse: the same
 * filters as the log query, aggregated server-side.
 */
export interface AuditLogStats {
  total_calls: number;
  avg_latency_ms: number;
  degraded_calls: number;
  error_calls: number;
}

// ---------------------------------------------------------------------------
// GraphRAG (M7-CONTRACTS.md sections 0.1-0.10 / 2). Shapes below were reconciled against the
// delivered server endpoints (GraphController + GraphSummaryResponse/GraphEntityResponse).
// ---------------------------------------------------------------------------

/** t_kb_task status for GRAPH_EXTRACT/GRAPH_CLEANUP rows (M7-CONTRACTS.md section 0.3). */
export type GraphTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';

/** t_kb_task.task_type values M7 introduced (section 0.4). */
export type GraphTaskType = 'GRAPH_EXTRACT' | 'GRAPH_CLEANUP';

/**
 * Latest graph task summary embedded in GraphSummary (section 0.10: "最近任务状态"), mirroring the
 * server's GraphSummaryResponse.GraphTask.
 *
 * Note the field names do NOT follow EvalRun's vocabulary: the task type is `type` (not
 * task_type) and the failure text is `error_message` (not fail_reason). Reading the EvalRun names
 * here silently yields undefined, which is exactly how the failure alert went missing.
 */
export interface GraphTaskSummary {
  task_id: string;
  type: GraphTaskType;
  status: GraphTaskStatus;
  /** Percent complete, 0-100; absent on tasks that have not reported progress yet. */
  progress?: number;
  /** Chunks the extraction pipeline skipped on output validation (section 0.3), not a task failure. */
  skipped_chunk_count?: number;
  /** Failure text, present only on FAILED tasks (the server omits nulls). */
  error_message?: string;
  created_at: string;
}

/**
 * GET /api/v1/kb/{kbId}/graph/summary response (section 0.10: "实体数/关系数/覆盖分片数/最近任务
 * 状态"). graph_enabled is carried here as well as on KnowledgeBase so the 知识图谱 tab's polling
 * loop refreshes the switch state and the extraction progress in lockstep -- verified present on
 * the server response. `latest_task` is omitted entirely (not null) when no task has ever run.
 */
export interface GraphSummary {
  graph_enabled: boolean;
  entity_count: number;
  relation_count: number;
  covered_chunk_count: number;
  latest_task?: GraphTaskSummary;
}

/** PUT /api/v1/kb/{kbId}/graph/config request body (section 0.10). */
export interface UpdateGraphConfigRequest {
  enabled: boolean;
}

/**
 * POST /api/v1/kb/{kbId}/graph/extract response (section 0.10: "手动触发全量重抽"): a bare
 * {task_id}, verified against GraphController#extract.
 */
export interface TriggerGraphExtractResponse {
  task_id: string;
}

/**
 * One (:Entity)-[:REL]->(:Entity) outgoing edge, embedded on GraphEntity below. Verified: the
 * server bundles each entity's outgoing edges onto the entity row itself (GraphEntityResponse
 * .relations), so the visualization needs no separate relations endpoint.
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

// ---------------------------------------------------------------------------
// Retrieval quality loop (M10-CONTRACTS.md sections 1/2)
// ---------------------------------------------------------------------------

/** t_kb_retrieval_feedback.verdict (M10-CONTRACTS.md section 1). */
export type FeedbackVerdict = 'GOOD' | 'BAD';

/**
 * t_kb_retrieval_feedback.status (M10-CONTRACTS.md section 1): NEW is the only non-terminal
 * state; CONVERTED and DISMISSED are both final server-side (a second convert/dismiss is
 * rejected with INVALID_PARAM), so the UI hides the row actions once a row leaves NEW.
 */
export type FeedbackStatus = 'NEW' | 'CONVERTED' | 'DISMISSED';

/**
 * t_kb_retrieval_feedback row (M10-CONTRACTS.md section 2.1). The raw query is returned on
 * purpose: the operator deciding whether to convert has to read the question actually asked.
 * doc_id is null when the chunk was already deleted at submission time.
 */
export interface RetrievalFeedbackEntry {
  feedback_id: string;
  kb_id: string;
  query: string;
  chunk_id: string;
  doc_id: string | null;
  verdict: FeedbackVerdict;
  status: FeedbackStatus;
  /** Which entrance filed the row (M16): the console debug screen or an Open API end user. */
  channel: FeedbackChannel;
  /** Caller-supplied end user tag of an OPEN_API row; null on console rows. */
  end_user_id: string | null;
  /** Evaluation case created from this row, null until converted. */
  converted_case_id: string | null;
  note: string | null;
  created_at: string;
}

/** t_kb_retrieval_feedback.channel (M16-CONTRACTS.md section 6): which entrance filed the row. */
export type FeedbackChannel = 'CONSOLE' | 'OPEN_API';

/** GET /api/v1/kb/{kbId}/retrieval-feedback query params (M10-CONTRACTS.md section 2.1). */
export interface ListRetrievalFeedbackParams {
  verdict?: FeedbackVerdict;
  status?: FeedbackStatus;
  channel?: FeedbackChannel;
  page?: number;
  size?: number;
}

/** POST /api/v1/retrieval-feedback/{feedbackId}/convert request body (M10-CONTRACTS.md section 2.1). */
export interface ConvertFeedbackRequest {
  dataset_id: string;
}

/** t_kb_search_insight.source (M10-CONTRACTS.md section 2.2): which entrance ran the retrieval. */
export type SearchInsightSource = 'CONSOLE' | 'OPEN_API';

/**
 * t_kb_search_insight row (M10-CONTRACTS.md section 2.2). query_digest is masked and truncated
 * server-side (never the raw query -- insight rows are statistics, not evidence); top_score is
 * null on zero hits; degraded lists the degradation markers the call carried (e.g. embedding
 * fallback), empty for a clean call.
 */
export interface SearchInsightEntry {
  insight_id: string;
  kb_id: string;
  source: SearchInsightSource;
  query_digest: string;
  result_count: number;
  top_score: number | null;
  zero_hit: boolean;
  degraded: string[];
  request_id: string | null;
  created_at: string;
}

/**
 * GET /api/v1/kb/{kbId}/search-insights query params (M10-CONTRACTS.md section 2.2).
 * from/to are ISO date-times, e.g. 2026-07-26T00:00:00.
 */
export interface ListSearchInsightParams {
  zero_hit?: boolean;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/** One zero-hit query group of the stats report, newest digest of the group + occurrence count. */
export interface TopZeroHitQuery {
  query_digest: string;
  count: number;
  last_at: string | null;
}

/** GET /api/v1/kb/{kbId}/search-insights/stats response (M10-CONTRACTS.md section 2.2). */
export interface SearchInsightStats {
  total: number;
  zero_hit_count: number;
  /** zero_hit_count / total, 0 when the window is empty. */
  zero_hit_rate: number;
  degraded_count: number;
  /** Most frequent zero-hit query groups, largest first (case/whitespace-normalized grouping). */
  top_zero_hit_queries: TopZeroHitQuery[];
}

// ---------------------------------------------------------------------------
// Web source / URL import (M12-CONTRACTS.md)
// ---------------------------------------------------------------------------

/**
 * t_kb_web_source.last_fetch_status (M12-CONTRACTS.md section 3.4). UNCHANGED and SKIPPED are
 * successes of a kind: the fetch worked but writing anything would have been wrong (page hash
 * identical / bound document sits in the recycle bin, respectively).
 */
export type WebSourceFetchStatus = 'SUCCESS' | 'UNCHANGED' | 'SKIPPED' | 'FAILED';

/**
 * t_kb_web_source row (M12-CONTRACTS.md section 1): one registered page URL and the outcome of
 * its last sync. doc_id/file_name stay null until the first successful fetch; the binding is
 * weak -- removing the registration never touches the document and vice versa.
 */
export interface WebSourceEntry {
  source_id: string;
  kb_id: string;
  url: string;
  doc_id: string | null;
  file_name: string | null;
  sync_enabled: boolean;
  /** M17-CONTRACTS.md section 1: fetch this source through a headless browser and store the rendered DOM. */
  render_js: boolean;
  last_fetch_status: WebSourceFetchStatus | null;
  last_fetch_at: string | null;
  last_error: string | null;
  created_at: string;
}

/** POST /api/v1/kb/{kbId}/web-sources request body (M12-CONTRACTS.md section 3.4). */
export interface RegisterWebSourceRequest {
  url: string;
  /** Defaults to true server-side when omitted. */
  sync_enabled?: boolean;
  /** M17: JS rendering, defaults to false server-side when omitted. */
  render_js?: boolean;
}

/**
 * PUT /api/v1/web-sources/{sourceId} request body (M17-CONTRACTS.md section 3.3): the mutable
 * switches of a registration. Both are optional; only the present ones are applied.
 */
export interface UpdateWebSourceRequest {
  sync_enabled?: boolean;
  render_js?: boolean;
}

/** 站点凭据的认证类型（M18）：BASIC 用户名密码；HEADER 任意请求头，覆盖 Bearer 与 Cookie。 */
export type WebAuthType = 'BASIC' | 'HEADER';

/**
 * GET /api/v1/web-credentials 响应行（M18）：按 host 挂的站点凭据，V22 起归属租户。响应里没有
 * secret 字段——不是省略，是接口层面就不存在，密码只进不出；租户同理不回传，列表本身已按租户裁剪。
 */
export interface WebCredentialEntry {
  credential_id: string;
  host: string;
  auth_type: WebAuthType;
  username: string | null;
  header_name: string | null;
  enabled: boolean;
  created_at: string;
}

/** POST /api/v1/web-credentials 请求体（M18）。 */
export interface CreateWebCredentialRequest {
  host: string;
  auth_type: WebAuthType;
  username?: string;
  secret: string;
  header_name?: string;
  enabled?: boolean;
}

/**
 * PUT /api/v1/web-credentials/{credentialId} 请求体（M18）：全部可选，缺省保持原值；secret 留空
 * 表示不改密码，所以停启用不需要重新输入密码。host 与认证类型故意不可改——换站点或换方式就是
 * 另一份凭据，删了重建。
 */
export interface UpdateWebCredentialRequest {
  username?: string;
  secret?: string;
  header_name?: string;
  enabled?: boolean;
}

// ---------------------------------------------------------------------------
// External data source connector (M14 contract section 2.3): S3/OSS compatible
// object store, scanned into the knowledge base as documents.
// ---------------------------------------------------------------------------

/**
 * ExtSourceResponse.last_sync_status (M14 contract section 2.3): outcome of the last whole-source
 * sync pass. PARTIAL means the scan ran but at least one object failed/was skipped; the per-object
 * detail lives on the item rows. null before the first sync.
 */
export type ExtSourceSyncStatus = 'SUCCESS' | 'PARTIAL' | 'FAILED';

/**
 * ExtSourceItemResponse.last_status (M14 contract section 2.3): per-object outcome of the last
 * sync visit. UNCHANGED (etag identical) and SKIPPED (bound document in the recycle bin) are
 * deliberate non-writes, not failures. null before the first visit.
 */
export type ExtSourceItemStatus = 'SUCCESS' | 'UNCHANGED' | 'SKIPPED' | 'FAILED';

/**
 * t_kb_ext_source row (M14 contract section 2.3): one registered object-store source and the
 * outcome of its last sync pass. secret_key is always the fixed mask on the way out; the update
 * endpoint treats a blank secret as "keep the stored one" so this view round-trips through an edit
 * form without destroying the credential. Binding is weak -- removing the source never touches the
 * documents it fed.
 */
export interface ExtSource {
  source_id: string;
  kb_id: string;
  /** Connector type routing key, `s3` in this milestone. */
  source_type: string;
  name: string;
  endpoint: string;
  region: string | null;
  bucket: string;
  prefix: string | null;
  access_key: string;
  /** Fixed mask ("******"), never the stored value. */
  secret_key: string;
  sync_enabled: boolean;
  last_sync_status: ExtSourceSyncStatus | null;
  last_sync_at: string | null;
  last_error: string | null;
  created_at: string;
}

/** t_kb_ext_source_item row (M14 contract section 2.3): per-object sync outcome of one source. */
export interface ExtSourceItem {
  object_key: string;
  /** Change marker of the last ingested body; null before the first ingest. */
  etag: string | null;
  /** Document the object feeds; null until the first successful ingest. */
  doc_id: string | null;
  last_status: ExtSourceItemStatus | null;
  last_error: string | null;
  last_sync_at: string | null;
  created_at: string;
}

/** POST /api/v1/kb/{kbId}/ext-sources request body (M14 contract section 2.3). */
export interface RegisterExtSourceRequest {
  /** Connector type routing key, `s3` in this milestone. */
  source_type: string;
  name: string;
  endpoint: string;
  region?: string;
  bucket: string;
  prefix?: string;
  access_key: string;
  secret_key: string;
  /** Defaults to true server-side when omitted. */
  sync_enabled?: boolean;
}

/**
 * PUT /api/v1/ext-sources/{sourceId} request body (M14 contract section 2.3): edits connection
 * details. No source_type -- the connector type is fixed at registration. A blank/absent secret_key
 * keeps the stored one; sync_enabled absent keeps current.
 */
export interface UpdateExtSourceRequest {
  name: string;
  endpoint: string;
  region?: string;
  bucket: string;
  prefix?: string;
  access_key: string;
  secret_key?: string;
  sync_enabled?: boolean;
}

/**
 * POST /api/v1/ext-sources/{sourceId}/sync response (M14 contract section 2.3): the scan runs off
 * the request thread, so this only acknowledges acceptance -- the outcome lands on the source and
 * item rows, watched by re-listing.
 */
export interface ExtSourceSyncAccepted {
  accepted: boolean;
}

/** POST /api/v1/ext-sources/{sourceId}/test response (M14 contract section 2.3): connection probe. */
export interface ExtSourceTestResult {
  up: boolean;
  detail: string;
}

// ---------------------------------------------------------------------------
// Multi-tenancy, document visibility and operation audit (M16-CONTRACTS.md)
// ---------------------------------------------------------------------------

export type TenantStatus = 'ENABLED' | 'DISABLED';

/**
 * t_kb_tenant row (M16-CONTRACTS.md section 3). The built-in default tenant hosts every row that
 * predates M16; it cannot be disabled or renamed away, which is why the actions column checks the
 * flag before offering anything.
 */
export interface TenantSummary {
  tenant_id: string;
  /** Stable identity used in index names; immutable after creation. */
  code: string;
  name: string;
  status: TenantStatus;
  builtin: boolean;
  created_at: string;
}

/**
 * POST /api/v1/tenants and PUT /api/v1/tenants/{tenantId} request body. The server validates both
 * fields on either call but only reads `name` on a rename -- code is fixed at creation.
 */
export interface SaveTenantRequest {
  code: string;
  name: string;
}

/**
 * t_kb_document.visibility (M16-CONTRACTS.md section 4). INHERIT means the knowledge base scope
 * decides alone; RESTRICTED additionally requires one of the granted roles to read the content.
 */
export type DocumentVisibility = 'INHERIT' | 'RESTRICTED';

/** GET /api/v1/kb/{kbId}/documents/{docId}/visibility response. */
export interface DocumentVisibilityView {
  visibility: DocumentVisibility;
  /** Roles allowed to read the content while RESTRICTED; empty otherwise. */
  role_ids: string[];
}

/** PUT /api/v1/kb/{kbId}/documents/{docId}/visibility request body. */
export interface UpdateDocumentVisibilityRequest {
  visibility: DocumentVisibility;
  role_ids?: string[];
}

/**
 * t_kb_operation_audit row (M16-CONTRACTS.md section 7): one successful write endpoint call and
 * who made it. Rows are written asynchronously after the response, so a just-performed action may
 * take a moment to appear in the list.
 */
export interface OperationAuditEntry {
  audit_id: string;
  user_id: string | null;
  username: string | null;
  module: string;
  action: string;
  target_type: string | null;
  target_id: string | null;
  /** Compact JSON of method + path, the "what exactly" behind module/action. */
  detail: string | null;
  client_ip: string | null;
  request_id: string | null;
  created_at: string;
}

/** GET /api/v1/operation-audits query params (M16-CONTRACTS.md section 7). */
export interface ListOperationAuditParams {
  module?: string;
  username?: string;
  target_id?: string;
  /** ISO date-times, e.g. 2026-07-26T00:00:00. */
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

// ---------------------------------------------------------------- memory library (M19)

/** One memory library card (GET /api/v1/memory-libraries). */
export interface MemoryLibrary {
  library_id: string;
  name: string;
  description: string | null;
  fragment_rule_count: number;
  profile_rule_count: number;
  node_count: number;
  entity_count: number;
  created_at: string;
  updated_at: string;
}

export interface MemoryLibraryPage {
  items: MemoryLibrary[];
  page: number;
  size: number;
  total: number;
}

/** Library detail: the card fields flattened together with both rule lists. */
export interface MemoryLibraryDetail extends MemoryLibrary {
  fragment_rules: MemoryFragmentRule[];
  profile_rules: MemoryProfileRule[];
}

export interface MemoryLibraryUpsertRequest {
  name: string;
  description?: string;
}

/** A memory fragment rule: how conversations are distilled into memory nodes. */
export interface MemoryFragmentRule {
  rule_id: string;
  library_id: string;
  name: string;
  instruction_type: 'DEFAULT' | 'CUSTOM';
  instruction: string | null;
  auto_update: boolean;
  /** 7/30/180 days, null means never expiring. */
  expire_days: number | null;
  extract_version: 'PRO' | 'LITE';
  builtin: boolean;
  node_count: number;
  created_at: string;
}

export interface MemoryFragmentRuleUpsertRequest {
  name: string;
  instruction_type: 'DEFAULT' | 'CUSTOM';
  instruction?: string;
  auto_update?: boolean;
  expire_days?: number | null;
  extract_version?: 'PRO' | 'LITE';
}

/** One user profile field definition. */
export interface MemoryProfileField {
  name: string;
  description: string | null;
  initial_value: string | null;
}

/** A user profile rule: which structured attributes are extracted per entity. */
export interface MemoryProfileRule {
  rule_id: string;
  library_id: string;
  name: string;
  extract_version: 'PRO' | 'LITE';
  fields: MemoryProfileField[];
  created_at: string;
}

export interface MemoryProfileRuleUpsertRequest {
  name: string;
  extract_version?: 'PRO' | 'LITE';
  fields: MemoryProfileField[];
}

/** One memory entity aggregate: a user_id with its node count and latest write. */
export interface MemoryEntity {
  user_id: string;
  node_count: number;
  updated_at: string;
}

export interface MemoryEntityPage {
  items: MemoryEntity[];
  page: number;
  size: number;
  total: number;
}

/** One memory node; score only present in search responses. */
export interface MemoryNode {
  memory_node_id: string;
  library_id: string;
  rule_id: string;
  user_id: string;
  content: string;
  source: 'EXTRACTED' | 'CUSTOM';
  meta_data: Record<string, unknown> | null;
  expire_at: string | null;
  created_at: string;
  updated_at: string;
  score?: number;
}

export interface MemoryNodePage {
  memory_nodes: MemoryNode[];
  page: number;
  size: number;
  total: number;
}

/** An entity's profile under one rule, initial values filled in for unfilled fields. */
export interface MemoryProfile {
  rule_id: string;
  rule_name: string;
  user_id: string;
  attributes: { name: string; value: string | null }[];
  updated_at: string | null;
}

/** POST .../search-debug payload, same shape the open API search takes. */
export interface MemorySearchDebugRequest {
  user_id: string;
  query: string;
  fragment_rule_id?: string;
  max_results?: number;
  intent_recognition?: boolean;
  rewrite?: boolean;
  rerank?: boolean;
  similarity_threshold?: number;
}

export interface MemorySearchResult {
  memory_nodes: MemoryNode[];
  profiles: MemoryProfile[];
  rewritten_query: string | null;
  intent_recalled: boolean;
}

/** A memory key row; never carries key material, only the display form. */
export interface MemoryAppKey {
  key_id: string;
  library_id: string;
  name: string;
  key_prefix: string;
  status: 'ENABLED' | 'DISABLED';
  qps_limit: number;
  last_used_at: string | null;
  created_at: string;
}

/** Issue/rotate response: the only shape that ever carries the plaintext key. */
export interface MemoryAppKeyIssued extends MemoryAppKey {
  api_key: string;
}

export interface MemoryAppKeyCreateRequest {
  name: string;
  qps_limit?: number;
}



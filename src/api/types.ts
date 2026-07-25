// Shared type definitions mirroring kb-rag-deploy/docs/M1-CONTRACTS.md section 5 (REST API contract).
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

export interface KnowledgeBase {
  kb_id: string;
  name: string;
  description: string | null;
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

// ---------------------------------------------------------------------------
// Retrieval
// ---------------------------------------------------------------------------

export type ScoreType = 'cosine' | 'bm25_rank';
export type RetrievalSource = 'vector' | 'bm25';

/** RetrievalNode, see M1-CONTRACTS.md section 5. */
export interface RetrievalNode {
  doc_id: string;
  document_version_id: string;
  chunk_id: string;
  chunk_type: string;
  content: string;
  score: number;
  score_type: ScoreType;
  retrieval_source: RetrievalSource;
  metadata: Record<string, unknown> | null;
  image_urls: string[];
  preview_url: string | null;
}

export interface SearchRequest {
  query: string;
  recall_top_k: number;
  top_n: number;
}

export interface SearchResponse {
  nodes: RetrievalNode[];
  request_id: string;
  degraded: string[];
}

// ---------------------------------------------------------------------------
// System
// ---------------------------------------------------------------------------

export interface ModelStatus {
  embedding_configured: boolean;
  vector_engine: string;
  provider: string | null;
  model: string | null;
}

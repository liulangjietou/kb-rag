import type { EmbeddingStatus, ProcessStatus, RetrievalSource, ScoreType } from '../api/types';

/** Ant Design Tag color + Chinese label per process_status, single source of truth for the UI. */
export const PROCESS_STATUS_META: Record<ProcessStatus, { color: string; label: string }> = {
  UPLOADED: { color: 'default', label: '已上传' },
  PARSING: { color: 'processing', label: '解析中' },
  PARSE_FAILED: { color: 'error', label: '解析失败' },
  PARSED: { color: 'processing', label: '已解析' },
  INDEXING: { color: 'processing', label: '索引中' },
  INDEXED: { color: 'success', label: '已就绪' },
  INDEX_FAILED: { color: 'error', label: '索引失败' },
};

export const EMBEDDING_STATUS_META: Record<EmbeddingStatus, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '待嵌入' },
  DONE: { color: 'success', label: '已嵌入' },
  FAILED: { color: 'error', label: '嵌入失败' },
  SKIPPED: { color: 'warning', label: '跳过(零Key)' },
};

export const SCORE_TYPE_META: Record<ScoreType, { color: string; label: string }> = {
  cosine: { color: 'blue', label: 'cosine' },
  bm25_rank: { color: 'purple', label: 'bm25_rank' },
};

export const RETRIEVAL_SOURCE_META: Record<RetrievalSource, { color: string; label: string }> = {
  vector: { color: 'geekblue', label: 'vector' },
  bm25: { color: 'gold', label: 'bm25' },
};

/** Known degraded reason codes mapped to a human readable Chinese explanation. */
export const DEGRADED_REASON_LABELS: Record<string, string> = {
  vector_route_unavailable: '向量检索不可用，已降级为 BM25 单路检索',
};

export function describeDegradedReason(reason: string): string {
  return DEGRADED_REASON_LABELS[reason] ?? reason;
}

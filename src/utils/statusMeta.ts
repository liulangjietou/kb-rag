import type {
  EmbeddingStatus,
  FusionMode,
  IkDictStatus,
  IkDictType,
  ProcessStatus,
  RetrievalSource,
  ScoreType,
} from '../api/types';

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

export const FUSION_MODE_META: Record<FusionMode, { label: string }> = {
  rrf: { label: 'RRF（倒数排名融合）' },
  weighted: { label: '加权归一化融合' },
};

export const IK_DICT_TYPE_META: Record<IkDictType, { color: string; label: string }> = {
  EXT: { color: 'success', label: '扩展词' },
  STOP: { color: 'default', label: '停用词' },
};

export const IK_DICT_STATUS_META: Record<IkDictStatus, { color: string; label: string }> = {
  ENABLED: { color: 'success', label: '已启用' },
  DISABLED: { color: 'default', label: '已停用' },
};

/** Known degraded reason codes mapped to a human readable Chinese explanation. */
export const DEGRADED_REASON_LABELS: Record<string, string> = {
  vector_route_unavailable: '向量检索不可用，已降级为 BM25 单路检索',
  query_rewrite_timeout: '查询改写超时或失败，已使用原始查询检索',
  rerank_timeout: '重排序超时，已使用融合排序结果',
  rerank_error: '重排序服务异常，已使用融合排序结果',
  threshold_inactive: 'BM25 单路检索下阈值过滤未生效',
};

export function describeDegradedReason(reason: string): string {
  return DEGRADED_REASON_LABELS[reason] ?? reason;
}

/**
 * Resolves the "threshold applied on" tag shown both in the applied info bar and on each
 * result card (M2-CONTRACTS.md section 5). Returns null when no threshold was configured at all.
 */
export function describeThresholdApplied(
  thresholdAppliedOn: ScoreType | null,
  degraded: string[],
): { color: string; label: string } | null {
  if (degraded.includes('threshold_inactive')) {
    return { color: 'warning', label: '阈值未生效（BM25 单路）' };
  }
  if (!thresholdAppliedOn) {
    return null;
  }
  return { color: 'processing', label: `阈值作用于 ${SCORE_TYPE_META[thresholdAppliedOn].label}` };
}

import type {
  EmbeddingStatus,
  FusionMode,
  IkDictStatus,
  IkDictType,
  ProcessStatus,
  RetrievalSource,
  ScoreType,
  ThresholdAppliedOn,
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

export const FUSION_MODE_META: Record<FusionMode, { color: string; label: string }> = {
  rrf: { color: 'processing', label: 'RRF（倒数排名融合）' },
  weighted: { color: 'processing', label: '加权归一化融合' },
};

export const IK_DICT_TYPE_META: Record<IkDictType, { color: string; label: string }> = {
  EXT: { color: 'success', label: '扩展词' },
  STOP: { color: 'default', label: '停用词' },
};

export const IK_DICT_STATUS_META: Record<IkDictStatus, { color: string; label: string }> = {
  ENABLED: { color: 'success', label: '已启用' },
  DISABLED: { color: 'default', label: '已停用' },
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

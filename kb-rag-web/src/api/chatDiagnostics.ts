const rerankStatuses = ['APPLIED', 'DISABLED', 'EMPTY_CANDIDATES', 'UNAVAILABLE', 'TIMEOUT', 'FAILED', 'SKIPPED'] as const;
export type RerankStatus = typeof rerankStatuses[number];

/** 预览阶段实测值；缺失和 null 均表示未采集，零是有效测量。 */
export interface ChatDiagnostics {
  request_id?: string;
  outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  failed_stage?: 'CONFIGURATION' | 'RETRIEVAL' | 'GENERATION' | null;
  configuration_ms?: number | null;
  retrieval_ms?: number | null;
  rerank_status?: RerankStatus | null;
  rerank_ms?: number | null;
  generation_ms?: number | null;
  first_delta_ms?: number | null;
  total_ms: number;
}

const durationFields = ['configuration_ms', 'retrieval_ms', 'rerank_ms', 'generation_ms', 'first_delta_ms', 'total_ms'] as const;

/** 可选诊断异常不破坏正文协议，也不把非法值变成零耗时。 */
export function readChatDiagnostics(value: unknown): ChatDiagnostics | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const data = value as Record<string, unknown>;
  if (typeof data.outcome !== 'string' || !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(data.outcome)) return undefined;
  if (typeof data.total_ms !== 'number') return undefined;
  if (data.failed_stage != null && (typeof data.failed_stage !== 'string'
    || !['CONFIGURATION', 'RETRIEVAL', 'GENERATION'].includes(data.failed_stage))) return undefined;
  for (const field of durationFields) {
    const duration = data[field];
    if (duration != null && (typeof duration !== 'number' || !Number.isSafeInteger(duration) || duration < 0)) return undefined;
  }
  if (data.rerank_status != null && !rerankStatuses.includes(data.rerank_status as RerankStatus)) return undefined;
  const measuredRerank = ['APPLIED', 'TIMEOUT', 'FAILED'].includes(data.rerank_status as string);
  if (measuredRerank !== (data.rerank_ms != null)) return undefined;
  return {
    request_id: typeof data.request_id === 'string' ? data.request_id : undefined,
    outcome: data.outcome as ChatDiagnostics['outcome'],
    failed_stage: data.failed_stage as ChatDiagnostics['failed_stage'],
    configuration_ms: data.configuration_ms as number | null | undefined,
    retrieval_ms: data.retrieval_ms as number | null | undefined,
    rerank_status: data.rerank_status as RerankStatus | null | undefined,
    rerank_ms: data.rerank_ms as number | null | undefined,
    generation_ms: data.generation_ms as number | null | undefined,
    first_delta_ms: data.first_delta_ms as number | null | undefined,
    total_ms: data.total_ms,
  };
}

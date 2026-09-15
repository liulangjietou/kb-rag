import { apiGet } from './request';

interface CorpusSummary {
  source: 'EMPTY' | 'FROZEN' | 'CURRENT' | 'UNAVAILABLE';
  complete: boolean;
  document_count?: number | null;
}
interface CorpusVersion { version_id: string; version: string }
export interface AppCorpusComparison {
  baseline: CorpusSummary;
  candidate: CorpusSummary;
  comparable: boolean;
  total?: number | null;
  added?: number | null;
  removed?: number | null;
  updated?: number | null;
  page: number;
  page_size: number;
  items: Array<{ kb_id: string; doc_id: string; file_name: string; change: 'ADDED' | 'REMOVED' | 'UPDATED';
    baseline?: CorpusVersion | null; candidate?: CorpusVersion | null }>;
}

/** 只读同应用版本的授权资料差异，省略基线表示明确的空集合。 */
export function compareAppVersionCorpus(candidateId: string, baselineId: string | null, page: number): Promise<AppCorpusComparison> {
  const query = new URLSearchParams({ page: String(page), page_size: '20' });
  if (baselineId) query.set('baseline_id', baselineId);
  return apiGet<AppCorpusComparison>(`/app-versions/${encodeURIComponent(candidateId)}/corpus-diff?${query}`);
}

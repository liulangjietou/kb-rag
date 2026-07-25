import { apiPost } from './request';
import type { SearchRequest, SearchResponse } from './types';

export function search(kbId: string, payload: SearchRequest): Promise<SearchResponse> {
  return apiPost<SearchResponse>(`/kb/${kbId}/search`, payload);
}

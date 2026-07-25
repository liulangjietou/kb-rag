import { apiDelete, apiGet, apiPost } from './request';
import type { CreateKbRequest, KnowledgeBase } from './types';

export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return apiGet<KnowledgeBase[]>('/kb');
}

export function getKnowledgeBase(kbId: string): Promise<KnowledgeBase> {
  return apiGet<KnowledgeBase>(`/kb/${kbId}`);
}

export function createKnowledgeBase(payload: CreateKbRequest): Promise<KnowledgeBase> {
  return apiPost<KnowledgeBase>('/kb', payload);
}

export function deleteKnowledgeBase(kbId: string): Promise<void> {
  return apiDelete<void>(`/kb/${kbId}`);
}

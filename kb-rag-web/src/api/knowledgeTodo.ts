import { apiGet } from './request';

export type KnowledgeTodoKind = 'PENDING_CONFIRM' | 'PENDING_REVIEW' | 'WEB_SOURCE_FAILED' | 'EXT_SOURCE_ATTENTION';
export interface KnowledgeTodo {
  kb_id: string;
  kb_name: string;
  kind: KnowledgeTodoKind;
  total: number;
  can_process: boolean;
}

/** 数量已经服务端按当前知识库授权裁剪，不从浏览器目录或来源时间猜测。 */
export function listKnowledgeTodos(): Promise<KnowledgeTodo[]> {
  return apiGet<KnowledgeTodo[]>('/me/knowledge-todos');
}

import type { DocumentFilters } from './components/DocumentFilterBar';

/** 首页只传固定事项种类，客户端任意筛选参数不会直接透传到后端。 */
export function knowledgeTodoTarget(search: string): {
  kind: string; tab: string; sourceTab: string; filters: DocumentFilters;
} {
  const kind = new URLSearchParams(search).get('todo');
  switch (kind) {
    case 'PENDING_CONFIRM': return { kind, tab: 'documents', sourceTab: 'webSources', filters: { process_status: 'PENDING_CONFIRM' } };
    case 'PENDING_REVIEW': return { kind, tab: 'documents', sourceTab: 'webSources', filters: { publish_status: 'PENDING_REVIEW' } };
    case 'WEB_SOURCE_FAILED': return { kind, tab: 'sources', sourceTab: 'webSources', filters: {} };
    case 'EXT_SOURCE_ATTENTION': return { kind, tab: 'sources', sourceTab: 'extSources', filters: {} };
    default: return { kind: '', tab: 'documents', sourceTab: 'webSources', filters: {} };
  }
}

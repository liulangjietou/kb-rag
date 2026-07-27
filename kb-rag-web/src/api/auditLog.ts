// Author: owlzhangfq@gmail.com
import { apiGet } from './request';
import type { ApiAuditLogEntry, AuditLogQueryParams, AuditLogStats, PageResult } from './types';

/**
 * Audit log query endpoints (M4c-CONTRACTS.md sections 1/3: "管理台提供查询界面与调用量统计，支持
 * 按 Key、应用版本与 target_stage 过滤与统计"). ASSUMPTION: neither the URL path nor the stats
 * response shape is spelled out by the contract beyond that prose description; see
 * AuditLogQueryParams/AuditLogStats's doc comments in types.ts.
 */

/** GET /api/v1/api-audit-logs?key_id=&from=&to=&target_stage=&page=. */
export function listAuditLogs(params?: AuditLogQueryParams): Promise<PageResult<ApiAuditLogEntry>> {
  return apiGet<PageResult<ApiAuditLogEntry>>('/api-audit-logs', params);
}

/** GET /api/v1/api-audit-logs/stats?key_id=&from=&to=&target_stage= (same filters, no paging). */
export function getAuditLogStats(params?: Omit<AuditLogQueryParams, 'page'>): Promise<AuditLogStats> {
  return apiGet<AuditLogStats>('/api-audit-logs/stats', params);
}

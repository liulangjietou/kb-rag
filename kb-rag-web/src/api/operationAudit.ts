// Author: owlzhangfq@gmail.com
import { apiGet } from './request';
import type { ListOperationAuditParams, OperationAuditEntry, PageResult } from './types';

/**
 * Operation audit query endpoints (M16-CONTRACTS.md section 7): who did what to which object,
 * one row per successful write endpoint call. Read-only by design -- audit rows are evidence,
 * so there is no update or delete to offer.
 */

/** GET /api/v1/operation-audits?module=&username=&target_id=&from=&to=&page=&size=. */
export function listOperationAudits(
  params?: ListOperationAuditParams,
): Promise<PageResult<OperationAuditEntry>> {
  return apiGet<PageResult<OperationAuditEntry>>('/operation-audits', params);
}

/** GET /api/v1/operation-audits/{auditId}: single row for the detail drawer. */
export function getOperationAudit(auditId: string): Promise<OperationAuditEntry> {
  return apiGet<OperationAuditEntry>(`/operation-audits/${auditId}`);
}

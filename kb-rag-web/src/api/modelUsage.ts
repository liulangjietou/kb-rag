// Author: owlzhangfq@gmail.com
// M24 platform-only model usage ledger, quota summary and effective price configuration.
import { apiGet, apiPut } from './request';
import type { ModelPrice, ModelUsageRecord, ModelUsageSummary, PageResult } from './types';

export function getModelUsageSummary(tenantId: string, month: string): Promise<ModelUsageSummary> {
  return apiGet<ModelUsageSummary>('/model-usage/summary', { tenant_id: tenantId, month });
}

export function listModelUsageRecords(
  tenantId: string,
  month: string,
  page = 1,
  size = 20,
): Promise<PageResult<ModelUsageRecord>> {
  return apiGet<PageResult<ModelUsageRecord>>('/model-usage/records', {
    tenant_id: tenantId,
    month,
    page,
    size,
  });
}

export function listModelPrices(): Promise<ModelPrice[]> {
  return apiGet<ModelPrice[]>('/model-usage/prices');
}

export function saveModelPrice(price: ModelPrice): Promise<ModelPrice> {
  return apiPut<ModelPrice>('/model-usage/prices', price);
}

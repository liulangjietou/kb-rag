import { apiGet } from './request';
import type { ModelStatus } from './types';

export function getModelStatus(): Promise<ModelStatus> {
  return apiGet<ModelStatus>('/system/model-status');
}

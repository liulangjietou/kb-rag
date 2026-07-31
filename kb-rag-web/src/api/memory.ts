// Author: owlzhangfq@gmail.com
import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type {
  MemoryAppKey,
  MemoryAppKeyCreateRequest,
  MemoryAppKeyIssued,
  MemoryEntityPage,
  MemoryFragmentRule,
  MemoryFragmentRuleUpsertRequest,
  MemoryLibrary,
  MemoryLibraryDetail,
  MemoryLibraryPage,
  MemoryLibraryUpsertRequest,
  MemoryNodePage,
  MemoryProfile,
  MemoryProfileRule,
  MemoryProfileRuleUpsertRequest,
  MemorySearchDebugRequest,
  MemorySearchResult,
} from './types';

/**
 * Memory library console endpoints (M19). Admin-JWT-authenticated, routed through the shared
 * client like every other management surface; the open API twin of these lives behind
 * /api/v1/memory/** and is called with a Memory Key by consuming agents, never from here.
 */

// ---------------------------------------------------------------- libraries

export function pageMemoryLibraries(params: {
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<MemoryLibraryPage> {
  return apiGet<MemoryLibraryPage>('/memory-libraries', params);
}

export function createMemoryLibrary(payload: MemoryLibraryUpsertRequest): Promise<MemoryLibrary> {
  return apiPost<MemoryLibrary>('/memory-libraries', payload);
}

export function getMemoryLibrary(libraryId: string): Promise<MemoryLibraryDetail> {
  return apiGet<MemoryLibraryDetail>(`/memory-libraries/${libraryId}`);
}

export function updateMemoryLibrary(
  libraryId: string,
  payload: MemoryLibraryUpsertRequest,
): Promise<MemoryLibrary> {
  return apiPut<MemoryLibrary>(`/memory-libraries/${libraryId}`, payload);
}

/** Cascades over rules, memories, profiles and keys; irrecoverable by design. */
export function deleteMemoryLibrary(libraryId: string): Promise<void> {
  return apiDelete<void>(`/memory-libraries/${libraryId}`);
}

// ---------------------------------------------------------------- fragment rules

export function listFragmentRules(libraryId: string): Promise<MemoryFragmentRule[]> {
  return apiGet<MemoryFragmentRule[]>(`/memory-libraries/${libraryId}/fragment-rules`);
}

export function createFragmentRule(
  libraryId: string,
  payload: MemoryFragmentRuleUpsertRequest,
): Promise<MemoryFragmentRule> {
  return apiPost<MemoryFragmentRule>(`/memory-libraries/${libraryId}/fragment-rules`, payload);
}

export function updateFragmentRule(
  libraryId: string,
  ruleId: string,
  payload: MemoryFragmentRuleUpsertRequest,
): Promise<MemoryFragmentRule> {
  return apiPut<MemoryFragmentRule>(
    `/memory-libraries/${libraryId}/fragment-rules/${ruleId}`,
    payload,
  );
}

/** Refused for the built-in rule; cascades over the memories the rule produced. */
export function deleteFragmentRule(libraryId: string, ruleId: string): Promise<void> {
  return apiDelete<void>(`/memory-libraries/${libraryId}/fragment-rules/${ruleId}`);
}

// ---------------------------------------------------------------- profile rules

export function listProfileRules(libraryId: string): Promise<MemoryProfileRule[]> {
  return apiGet<MemoryProfileRule[]>(`/memory-libraries/${libraryId}/profile-rules`);
}

export function createProfileRule(
  libraryId: string,
  payload: MemoryProfileRuleUpsertRequest,
): Promise<MemoryProfileRule> {
  return apiPost<MemoryProfileRule>(`/memory-libraries/${libraryId}/profile-rules`, payload);
}

export function updateProfileRule(
  libraryId: string,
  ruleId: string,
  payload: MemoryProfileRuleUpsertRequest,
): Promise<MemoryProfileRule> {
  return apiPut<MemoryProfileRule>(
    `/memory-libraries/${libraryId}/profile-rules/${ruleId}`,
    payload,
  );
}

/** Cascades over every profile extracted under the rule. */
export function deleteProfileRule(libraryId: string, ruleId: string): Promise<void> {
  return apiDelete<void>(`/memory-libraries/${libraryId}/profile-rules/${ruleId}`);
}

// ---------------------------------------------------------------- entities, nodes, profiles

export function pageMemoryEntities(
  libraryId: string,
  params: { user_id?: string; page?: number; size?: number },
): Promise<MemoryEntityPage> {
  return apiGet<MemoryEntityPage>(`/memory-libraries/${libraryId}/entities`, params);
}

export function pageMemoryNodes(
  libraryId: string,
  params: { user_id: string; rule_id?: string; page?: number; size?: number },
): Promise<MemoryNodePage> {
  return apiGet<MemoryNodePage>(`/memory-libraries/${libraryId}/nodes`, params);
}

export function deleteMemoryNode(libraryId: string, nodeId: string): Promise<void> {
  return apiDelete<void>(`/memory-libraries/${libraryId}/nodes/${nodeId}`);
}

export function listMemoryProfiles(
  libraryId: string,
  params: { user_id: string; rule_id?: string },
): Promise<MemoryProfile[]> {
  return apiGet<MemoryProfile[]>(`/memory-libraries/${libraryId}/profiles`, params);
}

/** Runs the open API search pipeline from the console, for tuning recall parameters. */
export function memorySearchDebug(
  libraryId: string,
  payload: MemorySearchDebugRequest,
): Promise<MemorySearchResult> {
  return apiPost<MemorySearchResult>(`/memory-libraries/${libraryId}/search-debug`, payload);
}

// ---------------------------------------------------------------- memory keys

export function listMemoryKeys(libraryId: string): Promise<MemoryAppKey[]> {
  return apiGet<MemoryAppKey[]>(`/memory-libraries/${libraryId}/keys`);
}

/** The only response that ever carries the plaintext key. */
export function issueMemoryKey(
  libraryId: string,
  payload: MemoryAppKeyCreateRequest,
): Promise<MemoryAppKeyIssued> {
  return apiPost<MemoryAppKeyIssued>(`/memory-libraries/${libraryId}/keys`, payload);
}

export function updateMemoryKeyStatus(
  libraryId: string,
  keyId: string,
  status: 'ENABLED' | 'DISABLED',
): Promise<void> {
  return apiPut<void>(`/memory-libraries/${libraryId}/keys/${keyId}/status`, { status });
}

/** Issues a new secret, invalidating the old one immediately. */
export function rotateMemoryKey(libraryId: string, keyId: string): Promise<MemoryAppKeyIssued> {
  return apiPost<MemoryAppKeyIssued>(`/memory-libraries/${libraryId}/keys/${keyId}/rotate`);
}

export function deleteMemoryKey(libraryId: string, keyId: string): Promise<void> {
  return apiDelete<void>(`/memory-libraries/${libraryId}/keys/${keyId}`);
}

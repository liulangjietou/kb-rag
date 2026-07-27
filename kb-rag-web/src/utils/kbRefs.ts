// Author: owlzhangfq@gmail.com
import type { AppVersionConfig, KbRef, KnowledgeBase } from '../api/types';

/**
 * Single read-side normalization point for AppVersionConfig.kb_refs (M5-CONTRACTS.md section 1:
 * "读侧兼容旧快照的单 kb_id 字段，视为 [{kb_id, weight:1}]"). Every call site that needs a
 * version's routed kbs must go through here instead of reading config.kb_refs/config.kb_id
 * directly, so the M4c/M5 shape compat lives in exactly one place (fast-fail defensive
 * programming should not be repeated per call site).
 */
export function resolveKbRefs(config: AppVersionConfig): KbRef[] {
  if (config.kb_refs && config.kb_refs.length > 0) {
    return config.kb_refs;
  }
  if (config.kb_id) {
    return [{ kb_id: config.kb_id, weight: 1 }];
  }
  return [];
}

/**
 * Defensive kb_id -> name lookup shared by every M5 display spot (version list summary, applied
 * info bar, result-card kb Tag). Falls back to the raw id instead of throwing when the kb list
 * hasn't loaded yet or the id is stale, mirroring statusMeta.ts's metaOf fallback convention.
 */
export function kbNameOf(kbs: KnowledgeBase[], kbId: string | null | undefined): string {
  if (!kbId) {
    return '未知知识库';
  }
  return kbs.find((kb) => kb.kb_id === kbId)?.name ?? kbId;
}

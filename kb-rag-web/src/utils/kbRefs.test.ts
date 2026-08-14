import { describe, expect, it } from 'vitest';
import type { AppVersionConfig, KnowledgeBase } from '../api/types';
import { kbNameOf, resolveKbRefs } from './kbRefs';

function config(overrides: Partial<AppVersionConfig>): AppVersionConfig {
  return overrides as AppVersionConfig;
}

describe('resolveKbRefs', () => {
  it('prefers the current multi-knowledge-base shape', () => {
    const refs = [{ kb_id: 'kb1', weight: 0.7 }, { kb_id: 'kb2', weight: 0.3 }];

    expect(resolveKbRefs(config({ kb_id: 'legacy', kb_refs: refs }))).toBe(refs);
  });

  it('normalizes a legacy single knowledge-base snapshot', () => {
    expect(resolveKbRefs(config({ kb_id: 'legacy' }))).toEqual([{ kb_id: 'legacy', weight: 1 }]);
  });

  it('returns an empty list when no binding exists', () => {
    expect(resolveKbRefs(config({}))).toEqual([]);
  });
});

describe('kbNameOf', () => {
  const knowledgeBases = [{ kb_id: 'kb1', name: '研发知识库' }] as KnowledgeBase[];

  it('resolves known ids and preserves stale ids for diagnosis', () => {
    expect(kbNameOf(knowledgeBases, 'kb1')).toBe('研发知识库');
    expect(kbNameOf(knowledgeBases, 'missing')).toBe('missing');
  });

  it('labels an absent id explicitly', () => {
    expect(kbNameOf(knowledgeBases, null)).toBe('未知知识库');
  });
});

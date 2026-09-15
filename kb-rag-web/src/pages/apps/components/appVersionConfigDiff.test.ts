import { expect, it } from 'vitest';
import type { AppVersionConfig } from '../../../api/types';
import { compareAppVersionConfig, formatConfigDifferenceValue } from './appVersionConfigDiff';

const config = (): AppVersionConfig => ({
  kb_refs: [{ kb_id: 'kb-a', weight: 1 }], retrieval: { recall_top_k: 50, top_n: 5 },
  prompt: { system_prompt: '依据资料回答', refusal_enabled: true, refusal_prompt: '',
    leak_guard_enabled: true, leak_guard_prompt: '', citation_enabled: true },
});

it('对象属性顺序不产生假差异，读取也不修改原快照', () => {
  const before = config();
  const frozen = JSON.stringify(before);
  const after = { prompt: before.prompt, retrieval: { top_n: 5, recall_top_k: 50 }, kb_refs: before.kb_refs };
  expect(compareAppVersionConfig(before, after)).toEqual([]);
  expect(JSON.stringify(before)).toBe(frozen);
});

it('旧单知识库与等价的当前知识库结构不显示伪变更', () => {
  const legacy = { ...config(), kb_refs: undefined, kb_id: 'kb-a' } as unknown as AppVersionConfig;
  expect(compareAppVersionConfig(legacy, config())).toEqual([]);
});

it('关闭、零值、模型、权重和长提示词差异都保留', () => {
  const before = config();
  const after = config();
  after.chat_model = 'candidate-model'; after.kb_refs[0].weight = 2;
  after.retrieval.score_threshold = 0; after.prompt.refusal_enabled = false;
  after.prompt.system_prompt = '新增约束\n保留原始资料含义';
  const diffs = compareAppVersionConfig(before, after);
  expect(diffs.map(row => row.path)).toEqual(['chat_model', 'kb_refs', 'prompt.refusal_enabled', 'prompt.system_prompt', 'retrieval.score_threshold']);
  expect(diffs.find(row => row.path === 'prompt.refusal_enabled')?.after).toBe(false);
  expect(diffs.find(row => row.path === 'retrieval.score_threshold')?.after).toBe(0);
  expect(formatConfigDifferenceValue(false)).toBe('关闭');
  expect(formatConfigDifferenceValue(0)).toBe('0');
  expect(formatConfigDifferenceValue(null)).toBe('未设置');
  expect(formatConfigDifferenceValue('')).toBe('空文本');
});

it('省略与 null 同为未设置，新增门禁指标可被识别', () => {
  const before = config(); before.chat_model = null;
  const after = config(); after.answer_gate = { enabled: true, min_faithfulness: 0.9 };
  expect(compareAppVersionConfig(before, after).map(row => row.path)).toEqual(['answer_gate.enabled', 'answer_gate.min_faithfulness']);
});

it('保留知识库次序变更，不能掩盖默认配置来源的改变', () => {
  const before = config(); before.kb_refs.push({ kb_id: 'kb-b', weight: 1 });
  const after = { ...before, kb_refs: [...before.kb_refs].reverse() };
  expect(compareAppVersionConfig(before, after).map(row => row.path)).toEqual(['kb_refs']);
});

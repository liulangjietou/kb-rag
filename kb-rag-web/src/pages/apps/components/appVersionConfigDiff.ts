import type { AppVersionConfig } from '../../../api/types';
import { resolveKbRefs } from '../../../utils/kbRefs';

export interface ConfigDifference {
  path: string;
  label: string;
  before: unknown;
  after: unknown;
}

const LABELS: Record<string, string> = {
  kb_refs: '关联知识库与权重', chat_model: '回答模型',
  'retrieval.recall_top_k': '检索候选数', 'retrieval.top_n': '答案参考资料数',
  'retrieval.score_threshold': '相关性阈值', 'retrieval.fusion_mode': '融合方式',
  'retrieval.w_vec': '向量检索权重', 'retrieval.rrf_k': '排名融合参数',
  'retrieval.rerank_enabled': '结果重排', 'retrieval.rewrite_enabled': '问题改写',
  'retrieval.graph_enabled': '知识图谱召回',
  'prompt.system_prompt': '系统提示词', 'prompt.refusal_enabled': '无依据拒答',
  'prompt.refusal_prompt': '拒答提示词', 'prompt.leak_guard_enabled': '提示词泄露保护',
  'prompt.leak_guard_prompt': '泄露保护提示词', 'prompt.citation_enabled': '答案引用',
  'routing.enabled': '知识库路由', 'routing.prompt': '路由提示词',
  'gate.min_hit_rate': '最低命中率', 'gate.min_recall': '最低召回率',
  'answer_gate.enabled': '答案质量门禁', 'answer_gate.min_score': '最低答案综合分',
  'answer_gate.min_faithfulness': '最低忠实度', 'answer_gate.min_citation_correctness': '最低引用正确性',
  'answer_gate.min_refusal_accuracy': '最低答拒决策准确率',
};

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 忽略 JSON 对象的属性顺序；数组顺序保留，首个知识库可能承担默认配置来源。 */
function normalized(value: unknown): unknown {
  if (value == null) return null;
  if (Array.isArray(value)) return value.map(normalized);
  if (isObject(value)) return Object.fromEntries(Object.keys(value).sort().map(key => [key, normalized(value[key])]));
  return value;
}

function snapshot(config: AppVersionConfig | null): Record<string, unknown> {
  if (!config) return {};
  const result: Record<string, unknown> = { ...config, kb_refs: resolveKbRefs(config) };
  delete result.kb_id;
  return result;
}

/** 比较保存的配置，不猜测草稿在提交测试时将得到的默认参数。 */
export function compareAppVersionConfig(baseline: AppVersionConfig | null, candidate: AppVersionConfig): ConfigDifference[] {
  const changes: ConfigDifference[] = [];
  const visit = (before: unknown, after: unknown, path: string) => {
    if ((isObject(before) && (isObject(after) || after == null)) || (before == null && isObject(after))) {
      const left = isObject(before) ? before : {};
      const right = isObject(after) ? after : {};
      for (const key of [...new Set([...Object.keys(left), ...Object.keys(right)])].sort()) {
        visit(left[key], right[key], path ? `${path}.${key}` : key);
      }
      return;
    }
    const left = normalized(before);
    const right = normalized(after);
    if (JSON.stringify(left) !== JSON.stringify(right)) changes.push({ path, label: LABELS[path] ?? path, before: left, after: right });
  };
  visit(snapshot(baseline), snapshot(candidate), '');
  return changes;
}

/** 区分未设置、空文本、关闭和零值，避免用真值判断吞掉配置差异。 */
export function formatConfigDifferenceValue(value: unknown): string {
  if (value == null) return '未设置';
  if (typeof value === 'boolean') return value ? '开启' : '关闭';
  if (typeof value === 'string') return value.length === 0 ? '空文本' : value;
  return JSON.stringify(value, null, 2);
}

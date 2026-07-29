// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { Alert, Button, Card, Drawer, Form, Input, InputNumber, Select, Space, Switch, Typography, message } from 'antd';
import { updateIndexConfig } from '../../../api/kb';
import type { ChatAggregationConfig, CleanRules, IndexConfig, MetadataRule, SplitStrategy } from '../../../api/types';
import { useModelStatus } from '../../../context/ModelStatusContext';
import { LLM_SPLIT_REQUIRES_CHAT_MODEL, SPLIT_STRATEGY_META } from '../../../utils/statusMeta';
import CleanRulesFields from './CleanRulesFields';

// M1 pipeline defaults (M1-CONTRACTS.md section 4 "按长度策略切分... 默认 600 token / 重叠 100").
const DEFAULT_CHUNK_MAX_TOKENS = 600;
const DEFAULT_CHUNK_OVERLAP = 100;
// M2-CONTRACTS.md section 1.4 parent_child defaults.
const DEFAULT_PARENT_MAX_TOKENS = 1200;
const DEFAULT_CHILD_MAX_TOKENS = 400;
const DEFAULT_CHILD_OVERLAP = 50;
// M3-CONTRACTS.md section 3.3 clean_rules defaults (schema sample in the contract).
const DEFAULT_CLEAN_RULES: CleanRules = {
  strip_header_footer: false,
  strip_watermark_patterns: [],
  regex_replacements: [],
  excel_header_join: true,
  extract_metadata: false,
  desensitize: { enabled: false, phone: true, id_card: true, bank_card: true, email: false },
};
// M3-CONTRACTS.md section 3.5 chat_aggregation default window, window_overlap added by
// M8-CONTRACTS.md section 0.5 (0 = the pre-M8 straight-cut behavior).
const DEFAULT_CHAT_AGGREGATION: ChatAggregationConfig = {
  window_minutes: 60,
  max_messages: 50,
  window_overlap: 0,
};
// M4a-CONTRACTS.md section 2.4 defaults.
const DEFAULT_HIDE_PARENT_WITH_DISABLED_CHILD = false;
const DEFAULT_INHERIT_DISABLE_ANNOTATION = true;
// Server default when a knowledge base has never had a strategy written (KnowledgeBaseService).
const DEFAULT_SPLIT_STRATEGY: SplitStrategy = 'fixed_length';
// M14 contract section 4 splitter fallbacks: 0 lets each strategy pick its own default.
const DEFAULT_SPLIT_HEADING_LEVEL = 0;
// M14 contract section 3.1: the console mirrors the server MetadataRule.KEY_PATTERN so an illegal
// key is caught before the PUT rather than coming back as INVALID_PARAM.
const METADATA_KEY_PATTERN = /^[a-z][a-z0-9_]{1,31}$/;
const MAX_METADATA_RULES = 10;

/** Copy shown under the split-strategy picker, one line per strategy (M14 contract section 4). */
function splitStrategyHint(strategy: SplitStrategy): string {
  switch (strategy) {
    case 'llm_semantic':
      return '由对话模型只输出切割点（不复述正文），结果落库缓存；适合无结构长文，成本高于定长切分';
    case 'separator':
      return '按分隔符切块，适合有固定分隔标记的文档；可选按正则解析分隔符';
    case 'heading':
      return '按 Markdown 标题层级切块，适合层级清晰的结构化文档';
    case 'page':
      return '每个源文档页面独立成片，适合页面边界即语义边界的文档';
    default:
      return '按 token 长度顺序切分，配合下方重叠长度使用';
  }
}

interface IndexConfigFormValues {
  split_strategy: SplitStrategy;
  split_separator?: string;
  split_separator_is_regex: boolean;
  split_heading_level: number;
  chunk_max_tokens: number;
  chunk_overlap: number;
  parent_child_enabled: boolean;
  parent_max_tokens: number;
  child_max_tokens: number;
  child_overlap: number;
  metadata_rules: MetadataRule[];
  multimodal_enabled: boolean;
  clean_rules: CleanRules;
  parse_preview_required: boolean;
  chat_aggregation: ChatAggregationConfig;
  hide_parent_with_disabled_child: boolean;
  inherit_disable_annotation: boolean;
}

interface IndexConfigDrawerProps {
  kbId: string;
  open: boolean;
  indexConfig: IndexConfig | null;
  onClose: () => void;
  /** Called after a successful save so the caller can refresh the KB detail + document list. */
  onSaved: () => void;
}

function toFormValues(config: IndexConfig | null): IndexConfigFormValues {
  return {
    split_strategy: config?.split_strategy ?? DEFAULT_SPLIT_STRATEGY,
    split_separator: config?.split_separator ?? undefined,
    split_separator_is_regex: config?.split_separator_is_regex ?? false,
    split_heading_level: config?.split_heading_level ?? DEFAULT_SPLIT_HEADING_LEVEL,
    chunk_max_tokens: config?.chunk_max_tokens ?? DEFAULT_CHUNK_MAX_TOKENS,
    chunk_overlap: config?.chunk_overlap ?? DEFAULT_CHUNK_OVERLAP,
    parent_child_enabled: config?.parent_child.enabled ?? false,
    parent_max_tokens: config?.parent_child.parent_max_tokens ?? DEFAULT_PARENT_MAX_TOKENS,
    child_max_tokens: config?.parent_child.child_max_tokens ?? DEFAULT_CHILD_MAX_TOKENS,
    child_overlap: config?.parent_child.child_overlap ?? DEFAULT_CHILD_OVERLAP,
    metadata_rules: config?.metadata_rules ?? [],
    multimodal_enabled: config?.multimodal_enabled ?? false,
    clean_rules: config?.clean_rules ?? DEFAULT_CLEAN_RULES,
    parse_preview_required: config?.parse_preview_required ?? false,
    // Rebuilt field by field rather than passed through: a pre-M8 stored config has no
    // window_overlap, and spreading it as-is would put `undefined` into the form and then into the
    // PUT body, which the server reads as 0.
    chat_aggregation: {
      window_minutes: config?.chat_aggregation?.window_minutes ?? DEFAULT_CHAT_AGGREGATION.window_minutes,
      max_messages: config?.chat_aggregation?.max_messages ?? DEFAULT_CHAT_AGGREGATION.max_messages,
      window_overlap: config?.chat_aggregation?.window_overlap ?? DEFAULT_CHAT_AGGREGATION.window_overlap,
    },
    hide_parent_with_disabled_child:
      config?.hide_parent_with_disabled_child ?? DEFAULT_HIDE_PARENT_WITH_DISABLED_CHILD,
    inherit_disable_annotation: config?.inherit_disable_annotation ?? DEFAULT_INHERIT_DISABLE_ANNOTATION,
  };
}

/**
 * Index config edit drawer (M2-CONTRACTS.md section 4): segment length/overlap and the
 * parent/child chunking switch + lengths. Saving triggers PUT index-config, which the server
 * uses to recompute current_config_fingerprint and mark stale documents (config_stale=1).
 */
export default function IndexConfigDrawer({ kbId, open, indexConfig, onClose, onSaved }: IndexConfigDrawerProps) {
  const [form] = Form.useForm<IndexConfigFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const parentChildEnabled = Form.useWatch('parent_child_enabled', form) ?? false;
  const splitStrategy = Form.useWatch('split_strategy', form) ?? DEFAULT_SPLIT_STRATEGY;
  // 'llm_semantic' is refused server-side without a chat model; disable the option rather than
  // letting the save round-trip into an INVALID_PARAM.
  const { modelStatus } = useModelStatus();
  const chatConfigured = modelStatus?.chat_configured ?? false;
  // F5 (M14 contract section 6.2): the multimodal switch stores regardless, but without a provider
  // indexing is skipped, so the console greys it out and says why rather than silently no-op.
  const multimodalConfigured = modelStatus?.multimodal_configured ?? false;

  useEffect(() => {
    if (open) {
      form.setFieldsValue(toFormValues(indexConfig));
    }
  }, [open, indexConfig, form]);

  const handleOk = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const result = await updateIndexConfig(kbId, {
        // The server replaces the whole index_config object from this body, so every key the kb
        // already has must be present here -- including the ones this drawer has no control for.
        split_strategy: values.split_strategy,
        // M14 section 4: only meaningful to the matching strategy, ignored by the others, but sent
        // every time so the server keeps a coherent record instead of a half-updated object.
        split_separator: values.split_separator?.trim() || undefined,
        split_separator_is_regex: values.split_separator_is_regex,
        split_heading_level: values.split_heading_level,
        chunk_max_tokens: values.chunk_max_tokens,
        chunk_overlap: values.chunk_overlap,
        parent_child: {
          enabled: values.parent_child_enabled,
          parent_max_tokens: values.parent_max_tokens,
          child_max_tokens: values.child_max_tokens,
          child_overlap: values.child_overlap,
        },
        // M14 section 3.1: an empty list clears every operator rule; sent whole so removals stick.
        metadata_rules: values.metadata_rules ?? [],
        multimodal_enabled: values.multimodal_enabled,
        clean_rules: values.clean_rules,
        parse_preview_required: values.parse_preview_required,
        chat_aggregation: values.chat_aggregation,
        hide_parent_with_disabled_child: values.hide_parent_with_disabled_child,
        inherit_disable_annotation: values.inherit_disable_annotation,
      });
      // The server already counted the documents its new fingerprint invalidated; reporting that
      // number beats the vague "使用旧配置的文档将标记为待重建" the drawer used to show.
      message.success(
        result.stale_documents > 0
          ? `索引配置已更新，${result.stale_documents} 篇文档标记为待重建`
          : '索引配置已更新，无文档需要重建',
      );
      onSaved();
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Drawer
      title="索引配置"
      open={open}
      onClose={onClose}
      width={560}
      destroyOnClose
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={handleOk}>
            保存
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        message="修改分段长度/重叠或父子分片配置后，已索引文档会被标记为使用旧配置，需要手动重建才能生效"
        style={{ marginBottom: 16 }}
      />
      <Form<IndexConfigFormValues> form={form} layout="vertical">
        <Typography.Title level={5}>切分策略</Typography.Title>
        <Form.Item
          name="split_strategy"
          label="切分方式"
          rules={[{ required: true, message: '请选择切分策略' }]}
          extra={splitStrategyHint(splitStrategy)}
        >
          <Select
            options={(Object.keys(SPLIT_STRATEGY_META) as SplitStrategy[]).map((code) => ({
              value: code,
              label: SPLIT_STRATEGY_META[code].label,
              disabled: code === 'llm_semantic' && !chatConfigured,
            }))}
          />
        </Form.Item>
        {!chatConfigured && (
          <Alert type="warning" showIcon message={LLM_SPLIT_REQUIRES_CHAT_MODEL} style={{ marginBottom: 16 }} />
        )}
        {splitStrategy === 'separator' && (
          <>
            <Form.Item name="split_separator" label="分隔符" extra="留空使用默认分隔符（连续空行）">
              <Input placeholder="例如：---、## 或自定义分隔符" allowClear />
            </Form.Item>
            <Form.Item
              name="split_separator_is_regex"
              label="分隔符按正则解析"
              valuePropName="checked"
              tooltip="开启后分隔符作为正则表达式匹配；关闭时按字面量匹配"
            >
              <Switch />
            </Form.Item>
          </>
        )}
        {splitStrategy === 'heading' && (
          <Form.Item
            name="split_heading_level"
            label="标题层级"
            extra="0 使用默认层级；按 Markdown # 号深度切分（1-6）"
            rules={[{ required: true, message: '请输入标题层级' }]}
          >
            <InputNumber min={0} max={6} style={{ width: '100%' }} />
          </Form.Item>
        )}
        {splitStrategy === 'page' && (
          <Alert
            type="info"
            showIcon
            message="按页切分：每个源文档页面独立成片，下方分段长度/重叠仅在单页过长时作为兑底上限"
            style={{ marginBottom: 16 }}
          />
        )}

        <Typography.Title level={5}>分段参数</Typography.Title>
        <Form.Item
          name="chunk_max_tokens"
          label={splitStrategy === 'llm_semantic' ? '分段长度上限（token）' : '分段长度（token）'}
          tooltip={
            splitStrategy === 'llm_semantic'
              ? 'LLM 语义切分下作为兜底上限：模型输出非法或超长时按此长度降级切分'
              : undefined
          }
          rules={[{ required: true, message: '请输入分段长度' }]}
        >
          <InputNumber min={50} max={4000} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="chunk_overlap" label="重叠长度（token）" rules={[{ required: true, message: '请输入重叠长度' }]}>
          <InputNumber min={0} max={2000} style={{ width: '100%' }} />
        </Form.Item>

        <Typography.Title level={5}>父子分片</Typography.Title>
        <Form.Item name="parent_child_enabled" label="启用父子分片" valuePropName="checked">
          <Switch />
        </Form.Item>
        {parentChildEnabled && (
          <>
            <Form.Item
              name="parent_max_tokens"
              label="父片长度（token）"
              rules={[{ required: true, message: '请输入父片长度' }]}
            >
              <InputNumber min={100} max={8000} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="child_max_tokens"
              label="子片长度（token）"
              rules={[{ required: true, message: '请输入子片长度' }]}
            >
              <InputNumber min={50} max={4000} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="child_overlap"
              label="子片重叠（token）"
              rules={[{ required: true, message: '请输入子片重叠' }]}
            >
              <InputNumber min={0} max={2000} style={{ width: '100%' }} />
            </Form.Item>
          </>
        )}

        <Typography.Title level={5}>元数据抽取</Typography.Title>
        <Typography.Paragraph type="secondary">
          在切片上戴固定值、正则捕获值或命中的关键词；抽取的元数据可用于检索时的结构过滤。
          最多 {MAX_METADATA_RULES} 条；修改后已索引文档需重建才生效。
        </Typography.Paragraph>
        <Form.List name="metadata_rules">
          {(fields, { add, remove }) => (
            <>
              {fields.map(({ key, name, ...restField }) => (
                <Card
                  key={key}
                  size="small"
                  style={{ marginBottom: 12 }}
                  extra={
                    <Button type="link" danger size="small" onClick={() => remove(name)}>
                      删除
                    </Button>
                  }
                >
                  <Form.Item
                    {...restField}
                    name={[name, 'key']}
                    label="元数据键"
                    rules={[
                      { required: true, message: '请输入元数据键' },
                      { pattern: METADATA_KEY_PATTERN, message: '小写字母开头，仅小写字母/数字/下划线，2-32 位' },
                    ]}
                  >
                    <Input placeholder="例如：doc_type" allowClear />
                  </Form.Item>
                  <Form.Item
                    {...restField}
                    name={[name, 'type']}
                    label="规则类型"
                    rules={[{ required: true, message: '请选择规则类型' }]}
                  >
                    <Select
                      options={[
                        { value: 'constant', label: '固定值' },
                        { value: 'regex', label: '正则捕获' },
                        { value: 'keyword_match', label: '关键词命中' },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item noStyle shouldUpdate>
                    {() => {
                      const type = form.getFieldValue(['metadata_rules', name, 'type']) as string | undefined;
                      if (type === 'constant') {
                        return (
                          <Form.Item
                            {...restField}
                            name={[name, 'value']}
                            label="固定值"
                            rules={[{ required: true, message: '请输入固定值' }]}
                          >
                            <Input placeholder="戴在每个切片上的固定值" allowClear />
                          </Form.Item>
                        );
                      }
                      if (type === 'regex') {
                        return (
                          <Form.Item
                            {...restField}
                            name={[name, 'pattern']}
                            label="正则表达式"
                            extra="捕获第一个分组；无分组时取整个匹配，最长 64 字符"
                            rules={[{ required: true, message: '请输入正则表达式' }, { max: 64, message: '最多 64 个字符' }]}
                          >
                            <Input placeholder="例如：合同编号[：:]\s*(\w+)" allowClear />
                          </Form.Item>
                        );
                      }
                      if (type === 'keyword_match') {
                        return (
                          <Form.Item
                            {...restField}
                            name={[name, 'keywords']}
                            label="关键词词表"
                            extra="记录切片命中的词；回车分隔，最多 50 个，单词最长 32 字符"
                            rules={[{ required: true, message: '请输入至少一个关键词' }]}
                          >
                            <Select mode="tags" tokenSeparators={[',', '\n']} placeholder="输入后回车添加" />
                          </Form.Item>
                        );
                      }
                      return null;
                    }}
                  </Form.Item>
                </Card>
              ))}
              {fields.length < MAX_METADATA_RULES && (
                <Button type="dashed" block onClick={() => add({ type: 'constant' })} style={{ marginBottom: 16 }}>
                  添加元数据规则
                </Button>
              )}
            </>
          )}
        </Form.List>

        <Typography.Title level={5}>多模态整页索引</Typography.Title>
        {!multimodalConfigured && (
          <Alert
            type="warning"
            showIcon
            message="多模态整页索引需已配置多模态向量模型，请先在系统设置中配置"
            style={{ marginBottom: 16 }}
          />
        )}
        <Form.Item
          name="multimodal_enabled"
          label="启用多模态整页索引"
          valuePropName="checked"
          tooltip="开启后为含图切片额外生成多模态向量，检索新增一路以图搜图召回；未配置多模态模型时开关仍会保存，但索引跳过"
        >
          <Switch disabled={!multimodalConfigured} />
        </Form.Item>

        <Typography.Title level={5}>清洗规则</Typography.Title>
        <CleanRulesFields form={form} namePath={['clean_rules']} />

        <Typography.Title level={5}>解析预览</Typography.Title>
        <Form.Item
          name="parse_preview_required"
          label="启用解析预览确认"
          valuePropName="checked"
          tooltip="开启后文档解析清洗完成即暂停在待确认状态，需人工预览确认或改规则重解析后才继续切分入库"
        >
          <Switch />
        </Form.Item>

        <Typography.Title level={5}>聊天聚合</Typography.Title>
        <Form.Item
          name={['chat_aggregation', 'window_minutes']}
          label="聚合窗口（分钟）"
          tooltip="按发送时间顺切窗口，超过窗口时长或消息数上限即切下一片"
          rules={[{ required: true, message: '请输入聚合窗口分钟数' }]}
        >
          <InputNumber min={1} max={1440} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name={['chat_aggregation', 'max_messages']}
          label="单窗口最大消息数"
          rules={[{ required: true, message: '请输入单窗口最大消息数' }]}
        >
          <InputNumber min={1} max={1000} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name={['chat_aggregation', 'window_overlap']}
          label="窗口重叠消息数"
          tooltip="下一窗口重复上一窗口末尾的若干条消息，避免跨窗口的问答被切断；0 为不重叠。须小于单窗口最大消息数的一半"
          dependencies={[['chat_aggregation', 'max_messages']]}
          rules={[
            { required: true, message: '请输入窗口重叠消息数' },
            // Mirrors the server's ChatAggregationParams.windowOverlapWithinBound so an invalid
            // value is caught here instead of coming back as INVALID_PARAM.
            ({ getFieldValue }) => ({
              validator(_, value: number) {
                const maxMessages = getFieldValue(['chat_aggregation', 'max_messages']) as number;
                if (value == null || maxMessages == null || value * 2 < maxMessages) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error(`窗口重叠消息数须小于 ${Math.ceil(maxMessages / 2)}`));
              },
            }),
          ]}
        >
          <InputNumber min={0} max={499} style={{ width: '100%' }} />
        </Form.Item>

        <Typography.Title level={5}>标注与父子片</Typography.Title>
        <Form.Item
          name="hide_parent_with_disabled_child"
          label="父片含禁用子片时隐藏"
          valuePropName="checked"
          tooltip="关闭时父片命中仍整段返回并在 metadata.disabled_child_ids 标注被禁用的子片；开启后只要父片含任意被禁用子片就整段不返回，用于严格合规场景"
        >
          <Switch />
        </Form.Item>
        <Form.Item
          name="inherit_disable_annotation"
          label="自动继承禁用类标注"
          valuePropName="checked"
          tooltip="文档升级新版本后，按 chunk_text_hash 完全相同精确匹配自动继承禁用标注；不做相似度匹配"
        >
          <Switch />
        </Form.Item>
      </Form>
    </Drawer>
  );
}

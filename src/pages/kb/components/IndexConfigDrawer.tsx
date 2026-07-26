import { useEffect, useState } from 'react';
import { Alert, Button, Drawer, Form, InputNumber, Space, Switch, Typography, message } from 'antd';
import { updateIndexConfig } from '../../../api/kb';
import type { ChatAggregationConfig, CleanRules, IndexConfig } from '../../../api/types';
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
// M3-CONTRACTS.md section 3.5 chat_aggregation default window.
const DEFAULT_CHAT_AGGREGATION: ChatAggregationConfig = { window_minutes: 60, max_messages: 50 };
// M4a-CONTRACTS.md section 2.4 defaults.
const DEFAULT_HIDE_PARENT_WITH_DISABLED_CHILD = false;
const DEFAULT_INHERIT_DISABLE_ANNOTATION = true;

interface IndexConfigFormValues {
  chunk_max_tokens: number;
  chunk_overlap: number;
  parent_child_enabled: boolean;
  parent_max_tokens: number;
  child_max_tokens: number;
  child_overlap: number;
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
    chunk_max_tokens: config?.chunk_max_tokens ?? DEFAULT_CHUNK_MAX_TOKENS,
    chunk_overlap: config?.chunk_overlap ?? DEFAULT_CHUNK_OVERLAP,
    parent_child_enabled: config?.parent_child.enabled ?? false,
    parent_max_tokens: config?.parent_child.parent_max_tokens ?? DEFAULT_PARENT_MAX_TOKENS,
    child_max_tokens: config?.parent_child.child_max_tokens ?? DEFAULT_CHILD_MAX_TOKENS,
    child_overlap: config?.parent_child.child_overlap ?? DEFAULT_CHILD_OVERLAP,
    clean_rules: config?.clean_rules ?? DEFAULT_CLEAN_RULES,
    parse_preview_required: config?.parse_preview_required ?? false,
    chat_aggregation: config?.chat_aggregation ?? DEFAULT_CHAT_AGGREGATION,
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

  useEffect(() => {
    if (open) {
      form.setFieldsValue(toFormValues(indexConfig));
    }
  }, [open, indexConfig, form]);

  const handleOk = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await updateIndexConfig(kbId, {
        chunk_max_tokens: values.chunk_max_tokens,
        chunk_overlap: values.chunk_overlap,
        parent_child: {
          enabled: values.parent_child_enabled,
          parent_max_tokens: values.parent_max_tokens,
          child_max_tokens: values.child_max_tokens,
          child_overlap: values.child_overlap,
        },
        clean_rules: values.clean_rules,
        parse_preview_required: values.parse_preview_required,
        chat_aggregation: values.chat_aggregation,
        hide_parent_with_disabled_child: values.hide_parent_with_disabled_child,
        inherit_disable_annotation: values.inherit_disable_annotation,
      });
      message.success('索引配置已更新，使用旧配置的文档将标记为待重建');
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
        <Typography.Title level={5}>分段参数</Typography.Title>
        <Form.Item
          name="chunk_max_tokens"
          label="分段长度（token）"
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
          tooltip="按发送时间无重叠顺切窗口，超过窗口时长或消息数上限即切下一片"
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

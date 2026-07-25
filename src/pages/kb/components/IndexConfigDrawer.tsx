import { useEffect, useState } from 'react';
import { Alert, Button, Drawer, Form, InputNumber, Space, Switch, Typography, message } from 'antd';
import { updateIndexConfig } from '../../../api/kb';
import type { IndexConfig } from '../../../api/types';

// M1 pipeline defaults (M1-CONTRACTS.md section 4 "按长度策略切分... 默认 600 token / 重叠 100").
const DEFAULT_CHUNK_MAX_TOKENS = 600;
const DEFAULT_CHUNK_OVERLAP = 100;
// M2-CONTRACTS.md section 1.4 parent_child defaults.
const DEFAULT_PARENT_MAX_TOKENS = 1200;
const DEFAULT_CHILD_MAX_TOKENS = 400;
const DEFAULT_CHILD_OVERLAP = 50;

interface IndexConfigFormValues {
  chunk_max_tokens: number;
  chunk_overlap: number;
  parent_child_enabled: boolean;
  parent_max_tokens: number;
  child_max_tokens: number;
  child_overlap: number;
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
      width={480}
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
      </Form>
    </Drawer>
  );
}

import { useState } from 'react';
import { Button, Divider, Drawer, Form, Input, Space, Switch, Typography, message } from 'antd';
import { updateKnowledgeBase } from '../../../api/kb';
import type { KnowledgeBase, UpdateKbRequest } from '../../../api/types';

interface KbSettingsDrawerProps {
  kb: KnowledgeBase;
  onClose: () => void;
  onSaved: () => void;
  onIndexConfig: () => void;
  onGovernance: (checked: boolean) => Promise<void>;
  governanceSaving: boolean;
}

/** 详情页设置入口只编排已有的资料、治理和索引配置接口。 */
export default function KbSettingsDrawer({
  kb,
  onClose,
  onSaved,
  onIndexConfig,
  onGovernance,
  governanceSaving,
}: KbSettingsDrawerProps) {
  const [form] = Form.useForm<UpdateKbRequest>();
  const [saving, setSaving] = useState(false);
  const save = async (values: UpdateKbRequest) => {
    setSaving(true);
    try {
      await updateKnowledgeBase(kb.kb_id, values);
      message.success('知识库已更新');
      onSaved();
      onClose();
    } catch {
      /* 请求层展示失败信息，保留抽屉中的输入供重试。 */
    } finally {
      setSaving(false);
    }
  };
  return (
    <Drawer
      title="知识库设置"
      open
      width={520}
      onClose={onClose}
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={() => form.submit()}>
            保存基本信息
          </Button>
        </Space>
      }
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ name: kb.name, description: kb.description }}
        onFinish={save}
      >
        <Form.Item name="name" label="知识库名称" rules={[{ required: true, message: '请输入知识库名称' }]}>
          <Input maxLength={64} />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea rows={3} maxLength={256} />
        </Form.Item>
      </Form>
      <Divider />
      <Typography.Title level={5}>发布治理</Typography.Title>
      <Space>
        <Switch
          aria-label="新文档需审核"
          checked={kb.review_required}
          loading={governanceSaving}
          onChange={onGovernance}
        />
        <span>新文档需审核</span>
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: 10 }}>
        开关即时保存，仅影响之后上传的新文档；已有文档不受影响。
      </Typography.Paragraph>
      <Divider />
      <Typography.Title level={5}>索引配置</Typography.Title>
      <Typography.Paragraph type="secondary">
        维护解析、清洗、分片与多模态策略。已入库文档需按新配置重建后生效。
      </Typography.Paragraph>
      <Button onClick={onIndexConfig}>编辑索引配置</Button>
    </Drawer>
  );
}

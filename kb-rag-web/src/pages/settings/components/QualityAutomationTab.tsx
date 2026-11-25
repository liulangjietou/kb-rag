import { useEffect, useState } from 'react';
import { Alert, Button, Form, Space, Switch, Typography, message } from 'antd';
import { getQualityAutomationConfig, updateQualityAutomationConfig } from '../../../api/system';
import type { QualityAutomationConfig } from '../../../api/types';

export default function QualityAutomationTab() {
  const [form] = Form.useForm<QualityAutomationConfig>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setLoading(true);
    getQualityAutomationConfig()
      .then((config) => form.setFieldsValue(config))
      .finally(() => setLoading(false));
  }, [form]);

  const handleSave = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await updateQualityAutomationConfig(values);
      message.success('质量自动化配置已保存');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <div>
        <Typography.Title level={5} style={{ marginBottom: 4 }}>员工差评自动建单</Typography.Title>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          开启后，员工提交「需改进」评价时，会为应用关联的每个知识库自动创建一条员工问答类质量问题（已存在则跳过）。
        </Typography.Paragraph>
      </div>
      <Form form={form} layout="vertical" disabled={loading}>
        <Form.Item name="auto_issue_from_employee_bad" valuePropName="checked" label="自动创建质量问题">
          <Switch />
        </Form.Item>
        <Alert showIcon type="info" message="默认关闭。自动建单不会包含员工身份，问题摘要来自当次提问。" />
      </Form>
      <Button type="primary" loading={saving} disabled={loading} onClick={() => void handleSave()}>保存</Button>
    </Space>
  );
}
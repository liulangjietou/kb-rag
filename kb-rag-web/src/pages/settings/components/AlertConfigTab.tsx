// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { Button, Form, Input, InputNumber, Space, Switch, Typography, message } from 'antd';
import { getAlertConfig, testAlertConfig, updateAlertConfig } from '../../../api/system';
import type { AlertConfig } from '../../../api/types';

const DEFAULT_TASK_FAIL_THRESHOLD = 3;
const DEFAULT_DEGRADE_RATE_THRESHOLD = 0.3;
const DEFAULT_SYNC_BACKLOG_THRESHOLD = 1000;
const DEFAULT_SILENCE_MINUTES = 30;

/**
 * Alert webhook settings tab (M3-CONTRACTS.md sections 3.6/4): webhook URL + on/off switch, the
 * three trigger thresholds (task fail streak / retrieval degrade rate / sync backlog), the
 * silence period, and a "send test message" action.
 */
export default function AlertConfigTab() {
  const [form] = Form.useForm<AlertConfig>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);

  useEffect(() => {
    setLoading(true);
    getAlertConfig()
      .then((config) => form.setFieldsValue(config))
      .finally(() => setLoading(false));
  }, [form]);

  const handleSave = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await updateAlertConfig(values);
      message.success('告警配置已保存');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setTesting(true);
    try {
      await testAlertConfig();
      message.success('测试消息已发送，请到 webhook 接收端查看');
    } finally {
      setTesting(false);
    }
  };

  return (
    <Form<AlertConfig>
      form={form}
      layout="vertical"
      style={{ maxWidth: 480 }}
      initialValues={{
        webhook_url: null,
        enabled: false,
        task_fail_threshold: DEFAULT_TASK_FAIL_THRESHOLD,
        degrade_rate_threshold: DEFAULT_DEGRADE_RATE_THRESHOLD,
        sync_backlog_threshold: DEFAULT_SYNC_BACKLOG_THRESHOLD,
        silence_minutes: DEFAULT_SILENCE_MINUTES,
      }}
    >
      <Typography.Paragraph type="secondary">
        未配置 webhook 时，触发条件仍会降级为 error 日志记录，不影响主流程
      </Typography.Paragraph>
      <Form.Item name="enabled" label="启用告警" valuePropName="checked">
        <Switch loading={loading} />
      </Form.Item>
      <Form.Item name="webhook_url" label="Webhook URL" tooltip="兼容钉钉/企微/Slack 的通用 text 消息结构">
        <Input placeholder="https://..." />
      </Form.Item>
      <Form.Item
        name="task_fail_threshold"
        label="同类任务连续失败阈值"
        rules={[{ required: true, message: '请输入阈值' }]}
      >
        <InputNumber min={1} max={100} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item
        name="degrade_rate_threshold"
        label="最近 5 分钟检索降级率阈值（0-1）"
        rules={[{ required: true, message: '请输入阈值' }]}
      >
        <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item
        name="sync_backlog_threshold"
        label="双写积压阈值（条）"
        rules={[{ required: true, message: '请输入阈值' }]}
      >
        <InputNumber min={1} max={1000000} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item
        name="silence_minutes"
        label="同类告警静默期（分钟）"
        tooltip="同一告警类型静默期内不重复发送，避免刷屏"
        rules={[{ required: true, message: '请输入静默期' }]}
      >
        <InputNumber min={1} max={1440} style={{ width: '100%' }} />
      </Form.Item>
      <Space>
        <Button type="primary" loading={saving} onClick={handleSave}>
          保存
        </Button>
        <Button loading={testing} onClick={handleTest}>
          发送测试
        </Button>
      </Space>
    </Form>
  );
}

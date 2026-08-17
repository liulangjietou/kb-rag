// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Button, Drawer, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listModelPrices, saveModelPrice } from '../../../api/modelUsage';
import type { ModelCapability, ModelPrice } from '../../../api/types';

interface Props {
  open: boolean;
  onClose: () => void;
}

interface PriceFormValues {
  provider: string;
  capability: ModelCapability;
  model: string;
  currency: string;
  inputPrice: number;
  outputPrice: number;
  enabled: boolean;
}

const CAPABILITIES: ModelCapability[] = ['CHAT', 'EMBEDDING', 'RERANK', 'VISION', 'MULTIMODAL_EMBEDDING'];
const MICROS_PER_CURRENCY = 1_000_000;

/** Global price configuration. Historical ledger rows keep the price snapshot they were settled with. */
export default function ModelPriceDrawer({ open, onClose }: Props) {
  const [prices, setPrices] = useState<ModelPrice[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ModelPrice | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<PriceFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setPrices(await listModelPrices());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) load();
  }, [open, load]);

  const openCreate = () => {
    setEditing(null);
    form.setFieldsValue({
      provider: 'dashscope',
      capability: 'CHAT',
      model: '',
      currency: 'CNY',
      inputPrice: 0,
      outputPrice: 0,
      enabled: true,
    });
    setModalOpen(true);
  };

  const openEdit = (price: ModelPrice) => {
    setEditing(price);
    form.setFieldsValue({
      provider: price.provider,
      capability: price.capability,
      model: price.model,
      currency: price.currency,
      inputPrice: price.input_price_micros / MICROS_PER_CURRENCY,
      outputPrice: price.output_price_micros / MICROS_PER_CURRENCY,
      enabled: price.enabled,
    });
    setModalOpen(true);
  };

  const submit = async (values: PriceFormValues) => {
    setSaving(true);
    try {
      await saveModelPrice({
        provider: values.provider.trim(),
        capability: values.capability,
        model: values.model.trim(),
        currency: values.currency.trim().toUpperCase(),
        input_price_micros: Math.round(values.inputPrice * MICROS_PER_CURRENCY),
        output_price_micros: Math.round(values.outputPrice * MICROS_PER_CURRENCY),
        enabled: values.enabled,
      });
      message.success('模型价格已保存；仅影响后续调用');
      setModalOpen(false);
      load();
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<ModelPrice> = [
    { title: '供应商', dataIndex: 'provider', width: 130 },
    { title: '能力', dataIndex: 'capability', width: 210 },
    { title: '模型', dataIndex: 'model', width: 220, render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
    { title: '币种', dataIndex: 'currency', width: 80 },
    {
      title: '输入价 / 百万 Token',
      dataIndex: 'input_price_micros',
      width: 180,
      render: (value: number, row) => `${row.currency} ${(value / MICROS_PER_CURRENCY).toFixed(6)}`,
    },
    {
      title: '输出价 / 百万 Token',
      dataIndex: 'output_price_micros',
      width: 180,
      render: (value: number, row) => `${row.currency} ${(value / MICROS_PER_CURRENCY).toFixed(6)}`,
    },
    { title: '状态', dataIndex: 'enabled', width: 90, render: (value: boolean) => value ? <Tag color="success">启用</Tag> : <Tag>停用</Tag> },
    { title: '操作', key: 'action', width: 80, render: (_, row) => <a onClick={() => openEdit(row)}>编辑</a> },
  ];

  return (
    <Drawer
      open={open}
      width={1000}
      title="模型价格配置"
      onClose={onClose}
      destroyOnClose
      extra={<Button type="primary" onClick={openCreate}>新增价格</Button>}
    >
      <Typography.Paragraph type="secondary">
        价格按“供应商 + 能力 + 模型”精确匹配，单位为每百万 Token。系统不会内置可能过期的价格；每笔调用会快照当时配置，修改后历史成本不重算。
      </Typography.Paragraph>
      <Table<ModelPrice>
        rowKey={(row) => `${row.provider}:${row.capability}:${row.model}`}
        loading={loading}
        columns={columns}
        dataSource={prices}
        pagination={false}
        scroll={{ x: 1100 }}
      />

      <Modal
        open={modalOpen}
        title={editing ? `编辑价格 - ${editing.model}` : '新增模型价格'}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form<PriceFormValues> form={form} layout="vertical" onFinish={submit} preserve={false}>
          <Space style={{ width: '100%' }} align="start">
            <Form.Item name="provider" label="供应商" rules={[{ required: true }]}>
              <Input disabled={!!editing} placeholder="dashscope" />
            </Form.Item>
            <Form.Item name="capability" label="能力" rules={[{ required: true }]}>
              <Select disabled={!!editing} style={{ width: 210 }} options={CAPABILITIES.map((value) => ({ value, label: value }))} />
            </Form.Item>
          </Space>
          <Form.Item name="model" label="模型标识" rules={[{ required: true, message: '请输入模型标识' }]}>
            <Input disabled={!!editing} placeholder="qwen-plus" />
          </Form.Item>
          <Form.Item name="currency" label="ISO 4217 币种" rules={[{ required: true }, { pattern: /^[A-Za-z]{3}$/, message: '请输入 3 位币种代码' }]}>
            <Input maxLength={3} placeholder="CNY" />
          </Form.Item>
          <Space style={{ width: '100%' }} align="start">
            <Form.Item name="inputPrice" label="输入价 / 百万 Token" rules={[{ required: true }]}>
              <InputNumber min={0} precision={6} style={{ width: 210 }} />
            </Form.Item>
            <Form.Item name="outputPrice" label="输出价 / 百万 Token" rules={[{ required: true }]}>
              <InputNumber min={0} precision={6} style={{ width: 210 }} />
            </Form.Item>
          </Space>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  );
}

// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { CopyOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import {
  copySourceMapping,
  createSourceMapping,
  deleteSourceMapping,
  listSourceMappings,
  updateSourceMapping,
} from '../../../api/sourceMapping';
import type { SourceMapping, SourceMappingType } from '../../../api/types';
import { SOURCE_MAPPING_TYPE_META, metaOf } from '../../../utils/statusMeta';

const SOURCE_TYPE_OPTIONS: { label: string; value: SourceMappingType }[] = [
  { label: 'CSV', value: 'csv' },
  { label: 'XLSX', value: 'xlsx' },
  { label: 'TXT', value: 'txt' },
  { label: 'HTML', value: 'html' },
];

interface MappingFormValues {
  name: string;
  source_type: SourceMappingType;
  profile_yaml: string;
}

/**
 * System settings "导入映射" tab (M8-CONTRACTS.md section 0.7): CRUD surface for
 * t_kb_source_mapping. Built-in rows (seeded idempotently by the server at startup) are
 * read-only in this UI -- no edit/delete action, only "复制为自定义" which clones the row into an
 * editable custom mapping; custom rows can be freely edited and deleted (with confirmation).
 * This same listing backs the chat-import wizard's mapping-profile dropdown (ChatImportWizard).
 */
export default function SourceMappingTab() {
  const [mappings, setMappings] = useState<SourceMapping[]>([]);
  const [loading, setLoading] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<SourceMapping | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [copyTarget, setCopyTarget] = useState<SourceMapping | null>(null);
  const [copyName, setCopyName] = useState('');
  const [copying, setCopying] = useState(false);
  const [form] = Form.useForm<MappingFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listSourceMappings();
      setMappings(result);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditTarget(null);
    form.resetFields();
    form.setFieldsValue({ source_type: 'csv' });
    setFormOpen(true);
  };

  const openEdit = (mapping: SourceMapping) => {
    setEditTarget(mapping);
    form.setFieldsValue({
      name: mapping.name,
      source_type: mapping.source_type,
      profile_yaml: mapping.profile_yaml,
    });
    setFormOpen(true);
  };

  const closeForm = () => {
    setFormOpen(false);
    form.resetFields();
    setEditTarget(null);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editTarget) {
        await updateSourceMapping(editTarget.mapping_id, values);
        message.success('映射已更新');
      } else {
        await createSourceMapping(values);
        message.success('映射已创建');
      }
      closeForm();
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (mapping: SourceMapping) => {
    await deleteSourceMapping(mapping.mapping_id);
    message.success('已删除');
    load();
  };

  const openCopy = (mapping: SourceMapping) => {
    setCopyTarget(mapping);
    setCopyName(`${mapping.name} 副本`);
  };

  const handleCopy = async () => {
    if (!copyTarget) return;
    setCopying(true);
    try {
      await copySourceMapping(copyTarget.mapping_id, { name: copyName || undefined });
      message.success('已复制为自定义映射');
      setCopyTarget(null);
      load();
    } finally {
      setCopying(false);
    }
  };

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建映射
        </Button>
      </div>

      <Table<SourceMapping>
        rowKey="mapping_id"
        loading={loading}
        dataSource={mappings}
        pagination={false}
        columns={[
          { title: '名称', dataIndex: 'name' },
          {
            title: '格式',
            dataIndex: 'source_type',
            width: 100,
            render: (type: SourceMappingType) => {
              const meta = metaOf(SOURCE_MAPPING_TYPE_META, type);
              return <Tag color={meta.color}>{meta.label}</Tag>;
            },
          },
          {
            title: '来源',
            dataIndex: 'is_builtin',
            width: 100,
            render: (isBuiltin: boolean) => (isBuiltin ? <Tag color="processing">内置</Tag> : <Tag>自定义</Tag>),
          },
          { title: '更新时间', dataIndex: 'updated_at', width: 180 },
          {
            title: '操作',
            width: 180,
            render: (_, record) =>
              record.is_builtin ? (
                <Button size="small" icon={<CopyOutlined />} onClick={() => openCopy(record)}>
                  复制为自定义
                </Button>
              ) : (
                <Space>
                  <Button size="small" onClick={() => openEdit(record)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title="确认删除该映射？"
                    okText="删除"
                    okType="danger"
                    cancelText="取消"
                    onConfirm={() => handleDelete(record)}
                  >
                    <Button size="small" danger>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
          },
        ]}
      />

      <Modal
        title={editTarget ? '编辑映射' : '新建映射'}
        open={formOpen}
        onOk={handleSubmit}
        onCancel={closeForm}
        confirmLoading={submitting}
        okText={editTarget ? '保存' : '创建'}
        cancelText="取消"
        width={640}
        destroyOnHidden
      >
        <Form<MappingFormValues> form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入映射名称' }]}>
            <Input placeholder="例如：微信 PC 端 TXT 导出" maxLength={64} />
          </Form.Item>
          <Form.Item name="source_type" label="格式" rules={[{ required: true, message: '请选择格式' }]}>
            <Select options={SOURCE_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item
            name="profile_yaml"
            label="映射档案（YAML）"
            rules={[{ required: true, message: '请输入 profile_yaml 内容' }]}
          >
            <Input.TextArea
              rows={16}
              placeholder="行首正则/命名捕获组或选择器配置，参见内置模板"
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="复制为自定义映射"
        open={copyTarget !== null}
        onOk={handleCopy}
        onCancel={() => setCopyTarget(null)}
        confirmLoading={copying}
        okText="复制"
        cancelText="取消"
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          将内置模板「{copyTarget?.name}」复制为一份可编辑的自定义映射，原内置模板不受影响。
        </Typography.Paragraph>
        <Input value={copyName} onChange={(e) => setCopyName(e.target.value)} placeholder="自定义映射名称" maxLength={64} />
      </Modal>
    </div>
  );
}

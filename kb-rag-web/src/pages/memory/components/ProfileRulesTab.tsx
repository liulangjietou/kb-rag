// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import {
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Radio,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { createProfileRule, deleteProfileRule, listProfileRules, updateProfileRule } from '../../../api/memory';
import type { MemoryProfileField, MemoryProfileRule, MemoryProfileRuleUpsertRequest } from '../../../api/types';

interface ProfileRuleFormValues {
  name: string;
  extract_version: 'PRO' | 'LITE';
  fields: MemoryProfileField[];
}

interface Props {
  libraryId: string;
  canWrite: boolean;
  onChanged: () => void;
}

/**
 * Profile rules tab: each rule defines up to 50 structured fields (name/description/initial
 * value) extracted per memory entity; up to 50 rules per library. Deleting a rule drops every
 * profile extracted under it.
 */
export default function ProfileRulesTab({ libraryId, canWrite, onChanged }: Props) {
  const [rules, setRules] = useState<MemoryProfileRule[]>([]);
  const [loading, setLoading] = useState(false);
  const [upsertOpen, setUpsertOpen] = useState(false);
  const [editing, setEditing] = useState<MemoryProfileRule | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<ProfileRuleFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRules(await listProfileRules(libraryId));
    } finally {
      setLoading(false);
    }
  }, [libraryId]);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ fields: [{ name: '', description: null, initial_value: null }] });
    setUpsertOpen(true);
  };

  const openEdit = (rule: MemoryProfileRule) => {
    setEditing(rule);
    form.setFieldsValue({
      name: rule.name,
      extract_version: rule.extract_version,
      fields: rule.fields,
    });
    setUpsertOpen(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    const payload: MemoryProfileRuleUpsertRequest = {
      name: values.name,
      extract_version: values.extract_version,
      fields: values.fields,
    };
    setSubmitting(true);
    try {
      if (editing) {
        await updateProfileRule(libraryId, editing.rule_id, payload);
        message.success('画像规则已更新');
      } else {
        await createProfileRule(libraryId, payload);
        message.success('画像规则创建成功');
      }
      setUpsertOpen(false);
      load();
      onChanged();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (ruleId: string) => {
    await deleteProfileRule(libraryId, ruleId);
    message.success('画像规则已删除，其抽取的用户画像已一并清理');
    load();
    onChanged();
  };

  return (
    <div>
      {canWrite && (
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
          <Tooltip title={rules.length >= 50 ? '每个记忆库最多 50 条用户画像规则' : undefined}>
            <Button type="primary" icon={<PlusOutlined />} disabled={rules.length >= 50} onClick={openCreate}>
              新建画像规则
            </Button>
          </Tooltip>
        </div>
      )}

      <Table<MemoryProfileRule>
        rowKey="rule_id"
        loading={loading}
        dataSource={rules}
        pagination={false}
        columns={[
          { title: '名称', dataIndex: 'name' },
          {
            title: '画像字段',
            dataIndex: 'fields',
            render: (fields: MemoryProfileField[]) => (
              <Space wrap>
                {fields.map((field) => (
                  <Tooltip key={field.name} title={field.description || undefined}>
                    <Tag>{field.name}</Tag>
                  </Tooltip>
                ))}
              </Space>
            ),
          },
          { title: '抽取版本', dataIndex: 'extract_version', width: 90 },
          { title: '创建时间', dataIndex: 'created_at', width: 170 },
          ...(canWrite
            ? [
                {
                  title: '操作',
                  width: 140,
                  render: (_: unknown, record: MemoryProfileRule) => (
                    <Space>
                      <Button size="small" onClick={() => openEdit(record)}>
                        编辑
                      </Button>
                      <Popconfirm
                        title="确认删除该画像规则？"
                        description="该规则抽取的所有用户画像将一并删除，此操作不可恢复"
                        okText="删除"
                        okType="danger"
                        cancelText="取消"
                        onConfirm={() => handleDelete(record.rule_id)}
                      >
                        <Button size="small" danger>
                          删除
                        </Button>
                      </Popconfirm>
                    </Space>
                  ),
                },
              ]
            : []),
        ]}
      />

      <Modal
        rootClassName="catalog-eval-modal"
        title={editing ? '编辑用户画像规则' : '新建用户画像规则'}
        open={upsertOpen}
        onOk={handleSubmit}
        onCancel={() => setUpsertOpen(false)}
        confirmLoading={submitting}
        okText={editing ? '保存' : '创建'}
        cancelText="取消"
        width={640}
        destroyOnClose
      >
        <Form<ProfileRuleFormValues> form={form} layout="vertical" initialValues={{ extract_version: 'PRO' }}>
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：基础用户画像" maxLength={64} showCount />
          </Form.Item>
          <Form.Item name="extract_version" label="抽取版本">
            <Radio.Group
              options={[
                { label: 'PRO', value: 'PRO' },
                { label: 'LITE', value: 'LITE' },
              ]}
            />
          </Form.Item>
          <Form.List
            name="fields"
            rules={[
              {
                validator: async (_, fields: MemoryProfileField[] | undefined) => {
                  if (!fields || fields.length === 0) {
                    return Promise.reject(new Error('至少配置一个画像字段'));
                  }
                  const names = fields.map((f) => f?.name?.trim()).filter(Boolean);
                  if (new Set(names).size !== names.length) {
                    return Promise.reject(new Error('画像字段名称不能重复'));
                  }
                  return Promise.resolve();
                },
              },
            ]}
          >
            {(fields, { add, remove }, { errors }) => (
              <>
                <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Typography.Text strong>画像字段（最多 50 个）</Typography.Text>
                  <Button
                    size="small"
                    icon={<PlusOutlined />}
                    disabled={fields.length >= 50}
                    onClick={() => add({ name: '', description: null, initial_value: null })}
                  >
                    添加字段
                  </Button>
                </div>
                {fields.map(({ key, name, ...restField }) => (
                  <Space key={key} align="baseline" style={{ display: 'flex', marginBottom: 4 }}>
                    <Form.Item
                      {...restField}
                      name={[name, 'name']}
                      rules={[{ required: true, message: '字段名必填' }]}
                      style={{ marginBottom: 8 }}
                    >
                      <Input placeholder="字段名，如：称呼" maxLength={64} style={{ width: 140 }} />
                    </Form.Item>
                    <Form.Item {...restField} name={[name, 'description']} style={{ marginBottom: 8 }}>
                      <Input placeholder="字段说明（可选）" maxLength={256} style={{ width: 220 }} />
                    </Form.Item>
                    <Form.Item {...restField} name={[name, 'initial_value']} style={{ marginBottom: 8 }}>
                      <Input placeholder="初始值（可选）" maxLength={256} style={{ width: 140 }} />
                    </Form.Item>
                    <MinusCircleOutlined onClick={() => remove(name)} />
                  </Space>
                ))}
                <Form.ErrorList errors={errors} />
              </>
            )}
          </Form.List>
        </Form>
      </Modal>
    </div>
  );
}

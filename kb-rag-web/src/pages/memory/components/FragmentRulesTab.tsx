// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import {
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import { createFragmentRule, deleteFragmentRule, listFragmentRules, updateFragmentRule } from '../../../api/memory';
import type { MemoryFragmentRule, MemoryFragmentRuleUpsertRequest } from '../../../api/types';

// Select cannot carry null as an option value, so "never expires" travels as 0 in the form
// and is mapped back to null right before the request goes out (the server stores null).
const EXPIRE_NEVER = 0;
const EXPIRE_OPTIONS = [
  { label: '7 天', value: 7 },
  { label: '30 天', value: 30 },
  { label: '180 天', value: 180 },
  { label: '永不过期', value: EXPIRE_NEVER },
];

interface FragmentRuleFormValues {
  name: string;
  instruction_type: 'DEFAULT' | 'CUSTOM';
  instruction?: string;
  auto_update: boolean;
  expire_days: number;
  extract_version: 'PRO' | 'LITE';
}

interface Props {
  libraryId: string;
  canWrite: boolean;
  onChanged: () => void;
}

/**
 * Fragment rules tab: up to 50 rules per library, the built-in「默认项目」rule is editable but
 * never deletable. CUSTOM rules require an extraction instruction; DEFAULT ones ignore it.
 */
export default function FragmentRulesTab({ libraryId, canWrite, onChanged }: Props) {
  const [rules, setRules] = useState<MemoryFragmentRule[]>([]);
  const [loading, setLoading] = useState(false);
  const [upsertOpen, setUpsertOpen] = useState(false);
  const [editing, setEditing] = useState<MemoryFragmentRule | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<FragmentRuleFormValues>();
  const instructionType = Form.useWatch('instruction_type', form) ?? 'DEFAULT';

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRules(await listFragmentRules(libraryId));
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
    setUpsertOpen(true);
  };

  const openEdit = (rule: MemoryFragmentRule) => {
    setEditing(rule);
    form.setFieldsValue({
      name: rule.name,
      instruction_type: rule.instruction_type,
      instruction: rule.instruction ?? undefined,
      auto_update: rule.auto_update,
      expire_days: rule.expire_days ?? EXPIRE_NEVER,
      extract_version: rule.extract_version,
    });
    setUpsertOpen(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    const payload: MemoryFragmentRuleUpsertRequest = {
      name: values.name,
      instruction_type: values.instruction_type,
      instruction: values.instruction_type === 'CUSTOM' ? values.instruction : undefined,
      auto_update: values.auto_update,
      expire_days: values.expire_days === EXPIRE_NEVER ? null : values.expire_days,
      extract_version: values.extract_version,
    };
    setSubmitting(true);
    try {
      if (editing) {
        await updateFragmentRule(libraryId, editing.rule_id, payload);
        message.success('规则已更新');
      } else {
        await createFragmentRule(libraryId, payload);
        message.success('规则创建成功');
      }
      setUpsertOpen(false);
      load();
      onChanged();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (ruleId: string) => {
    await deleteFragmentRule(libraryId, ruleId);
    message.success('规则已删除，其抽取的记忆节点已一并清理');
    load();
    onChanged();
  };

  return (
    <div>
      {canWrite && (
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
          <Tooltip title={rules.length >= 50 ? '每个记忆库最多 50 条记忆片段规则' : undefined}>
            <Button type="primary" icon={<PlusOutlined />} disabled={rules.length >= 50} onClick={openCreate}>
              新建规则
            </Button>
          </Tooltip>
        </div>
      )}

      <Table<MemoryFragmentRule>
        rowKey="rule_id"
        loading={loading}
        dataSource={rules}
        pagination={false}
        columns={[
          {
            title: '名称',
            dataIndex: 'name',
            render: (name: string, record) => (
              <Space>
                {name}
                {record.builtin && <Tag color="blue">预置</Tag>}
              </Space>
            ),
          },
          {
            title: '抽取指令',
            dataIndex: 'instruction_type',
            width: 220,
            render: (type: string, record) =>
              type === 'DEFAULT' ? (
                <Tag>默认指令</Tag>
              ) : (
                <Tooltip title={record.instruction}>
                  <Tag color="purple">自定义指令</Tag>
                </Tooltip>
              ),
          },
          {
            title: '自动更新',
            dataIndex: 'auto_update',
            width: 90,
            render: (v: boolean) => (v ? '开启' : '关闭'),
          },
          {
            title: '过期时间',
            dataIndex: 'expire_days',
            width: 100,
            render: (v: number | null) => (v == null ? '永不过期' : `${v} 天`),
          },
          { title: '抽取版本', dataIndex: 'extract_version', width: 90 },
          { title: '记忆节点数', dataIndex: 'node_count', width: 100 },
          { title: '创建时间', dataIndex: 'created_at', width: 170 },
          ...(canWrite
            ? [
                {
                  title: '操作',
                  width: 140,
                  render: (_: unknown, record: MemoryFragmentRule) => (
                    <Space>
                      <Button size="small" onClick={() => openEdit(record)}>
                        编辑
                      </Button>
                      {record.builtin ? (
                        <Tooltip title="预置默认规则不可删除">
                          <Button size="small" danger disabled>
                            删除
                          </Button>
                        </Tooltip>
                      ) : (
                        <Popconfirm
                          title="确认删除该规则？"
                          description="该规则抽取的记忆节点将一并删除，此操作不可恢复"
                          okText="删除"
                          okType="danger"
                          cancelText="取消"
                          onConfirm={() => handleDelete(record.rule_id)}
                        >
                          <Button size="small" danger>
                            删除
                          </Button>
                        </Popconfirm>
                      )}
                    </Space>
                  ),
                },
              ]
            : []),
        ]}
      />

      <Modal
        title={editing ? '编辑记忆片段规则' : '新建记忆片段规则'}
        open={upsertOpen}
        onOk={handleSubmit}
        onCancel={() => setUpsertOpen(false)}
        confirmLoading={submitting}
        okText={editing ? '保存' : '创建'}
        cancelText="取消"
        destroyOnClose
      >
        <Form<FragmentRuleFormValues>
          form={form}
          layout="vertical"
          initialValues={{
            instruction_type: 'DEFAULT',
            auto_update: true,
            expire_days: 180,
            extract_version: 'PRO',
          }}
        >
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：售后会话偏好抽取" maxLength={64} showCount />
          </Form.Item>
          <Form.Item name="instruction_type" label="抽取指令">
            <Radio.Group
              options={[
                { label: '默认指令', value: 'DEFAULT' },
                { label: '自定义指令', value: 'CUSTOM' },
              ]}
            />
          </Form.Item>
          {instructionType === 'CUSTOM' && (
            <Form.Item
              name="instruction"
              label="自定义抽取指令"
              rules={[{ required: true, message: '自定义指令类型必须填写抽取指令' }]}
            >
              <Input.TextArea rows={4} maxLength={2000} showCount placeholder="描述希望从对话中抽取哪些记忆，例如：只记录用户明确表达的偏好与禁忌" />
            </Form.Item>
          )}
          <Form.Item name="auto_update" label="自动更新" valuePropName="checked" extra="开启后，语义重复的新记忆会覆盖旧记忆，而不是并存">
            <Switch />
          </Form.Item>
          <Form.Item name="expire_days" label="记忆过期时间">
            <Select options={EXPIRE_OPTIONS} />
          </Form.Item>
          <Form.Item name="extract_version" label="抽取版本" extra="PRO 使用更强的抽取模型，LITE 更快更省">
            <Radio.Group
              options={[
                { label: 'PRO', value: 'PRO' },
                { label: 'LITE', value: 'LITE' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

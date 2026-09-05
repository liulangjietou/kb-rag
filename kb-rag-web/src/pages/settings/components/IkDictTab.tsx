import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, Modal, Popconfirm, Radio, Space, Switch, Table, Tag, message } from 'antd';
import {
  createIkDictEntry,
  deleteIkDictEntry,
  listIkDict,
  updateIkDictStatus,
} from '../../../api/system';
import type { IkDictEntry, IkDictType } from '../../../api/types';
import { IK_DICT_TYPE_META, metaOf } from '../../../utils/statusMeta';

const DEFAULT_PAGE = 1;

interface CreateIkDictFormValues {
  word: string;
  dict_type: IkDictType;
}

/**
 * ik 词典管理 tab (M2-CONTRACTS.md section 3/5): paginated word list, add-entry modal,
 * delete confirmation, enable/disable toggle. Entries are identified by `word` (the
 * table's unique key) since t_kb_ik_dict has no dedicated business id column.
 */
export default function IkDictTab() {
  const [entries, setEntries] = useState<IkDictEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(DEFAULT_PAGE);
  const [pageSize, setPageSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<CreateIkDictFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listIkDict({ page });
      setEntries(result.items);
      setTotal(result.total);
      setPageSize(result.size || 10);
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    load();
  }, [load]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await createIkDictEntry(values);
      message.success('词条已新增');
      form.resetFields();
      setAddModalOpen(false);
      setPage(DEFAULT_PAGE);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (word: string) => {
    await deleteIkDictEntry(word);
    message.success('词条已删除');
    load();
  };

  const handleToggleStatus = async (entry: IkDictEntry, enabled: boolean) => {
    await updateIkDictStatus(entry.word, enabled ? 'ENABLED' : 'DISABLED');
    message.success(enabled ? '已启用' : '已停用');
    load();
  };

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setAddModalOpen(true)}>
          新增词条
        </Button>
      </div>
      <Table<IkDictEntry>
        rowKey="word"
        loading={loading}
        dataSource={entries}
        pagination={{
          current: page,
          pageSize,
          total,
          onChange: setPage,
          showSizeChanger: false,
        }}
        columns={[
          { title: '词', dataIndex: 'word' },
          {
            title: '类型',
            dataIndex: 'dict_type',
            width: 120,
            render: (type: IkDictType) => {
              const meta = metaOf(IK_DICT_TYPE_META, type);
              return <Tag color={meta.color}>{meta.label}</Tag>;
            },
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 120,
            render: (_status, record: IkDictEntry) => (
              <Switch
                checked={record.status === 'ENABLED'}
                checkedChildren="启用"
                unCheckedChildren="停用"
                onChange={(checked) => handleToggleStatus(record, checked)}
              />
            ),
          },
          { title: '更新时间', dataIndex: 'updated_at', width: 180 },
          {
            title: '操作',
            width: 100,
            render: (_, record: IkDictEntry) => (
              <Popconfirm title="确认删除该词条？" okText="删除" okType="danger" cancelText="取消" onConfirm={() => handleDelete(record.word)}>
                <Button size="small" danger>
                  删除
                </Button>
              </Popconfirm>
            ),
          },
        ]}
      />

      <Modal
        title="新增词条"
        open={addModalOpen}
        onOk={handleCreate}
        onCancel={() => {
          form.resetFields();
          setAddModalOpen(false);
        }}
        confirmLoading={submitting}
        okText="新增"
        cancelText="取消"
        destroyOnHidden
      >
        <Form<CreateIkDictFormValues> form={form} layout="vertical" initialValues={{ dict_type: 'EXT' }}>
          <Form.Item name="word" label="词" rules={[{ required: true, message: '请输入词条内容' }]}>
            <Input placeholder="例如：知识库" maxLength={64} />
          </Form.Item>
          <Form.Item name="dict_type" label="类型" rules={[{ required: true }]}>
            <Radio.Group>
              <Space direction="vertical">
                <Radio value="EXT">扩展词（EXT，参与分词）</Radio>
                <Radio value="STOP">停用词（STOP，检索时忽略）</Radio>
              </Space>
            </Radio.Group>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

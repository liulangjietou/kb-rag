// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  Modal,
  Pagination,
  Row,
  Space,
  Spin,
  Statistic,
  Typography,
  message,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import {
  createMemoryLibrary,
  deleteMemoryLibrary,
  pageMemoryLibraries,
  updateMemoryLibrary,
} from '../../api/memory';
import type { MemoryLibrary, MemoryLibraryUpsertRequest } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import ResourceMenu from '../../components/ResourceMenu';
import PageHeader from '../../components/PageHeader';

const PAGE_SIZE = 12;

/**
 * Memory library console entry: paged cards with per-library counters, plus create/edit/delete.
 * memory:read alone gets the list and the detail drill-down; every mutation needs memory:write
 * and the server enforces both again.
 */
export default function MemoryLibraryListPage() {
  const [libraries, setLibraries] = useState<MemoryLibrary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [upsertOpen, setUpsertOpen] = useState(false);
  const [editing, setEditing] = useState<MemoryLibrary | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<MemoryLibraryUpsertRequest>();
  const navigate = useNavigate();
  const { can } = useAuth();
  const canWrite = can(PERMISSIONS.MEMORY_WRITE);

  const load = useCallback(async (targetPage: number, kw: string) => {
    setLoading(true);
    try {
      const result = await pageMemoryLibraries({
        keyword: kw || undefined,
        page: targetPage,
        size: PAGE_SIZE,
      });
      setLibraries(result.items);
      setTotal(result.total);
      setPage(result.page);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(1, '');
  }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setUpsertOpen(true);
  };

  const openEdit = (library: MemoryLibrary) => {
    setEditing(library);
    form.setFieldsValue({ name: library.name, description: library.description ?? undefined });
    setUpsertOpen(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await updateMemoryLibrary(editing.library_id, values);
        message.success('记忆库已更新');
      } else {
        await createMemoryLibrary(values);
        message.success('记忆库创建成功，已内置「默认项目」记忆片段规则');
      }
      setUpsertOpen(false);
      load(editing ? page : 1, keyword);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (libraryId: string) => {
    await deleteMemoryLibrary(libraryId);
    message.success('记忆库已删除');
    load(page, keyword);
  };

  return (
    <div className="catalog-eval-page catalog-list-page">
      <PageHeader
        eyebrow="AGENT MEMORY / 智能体记忆"
        title="记忆库"
        description="沉淀可检索的长期记忆、结构化实体与画像规则，并通过 Memory Key 安全接入智能体。"
        actions={
          <Space className="catalog-page-tools">
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="按名称搜索"
              style={{ width: 220 }}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={() => load(1, keyword)}
              onClear={() => load(1, '')}
            />
            {canWrite && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                新建记忆库
              </Button>
            )}
          </Space>
        }
      />

      <Spin spinning={loading}>
        {!loading && libraries.length === 0 ? (
          <Empty
            description={
              canWrite
                ? '还没有记忆库，点击右上角「新建记忆库」开始创建，随后可签发 Memory Key 供智能体调用'
                : '当前账号暂无可访问的记忆库，如需开通请联系管理员'
            }
          >
            {canWrite && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                立即新建
              </Button>
            )}
          </Empty>
        ) : (
          <Row gutter={[16, 16]}>
            {libraries.map((library) => (
              <Col key={library.library_id} xs={24} sm={12} xl={8} xxl={6}>
                <Card
                  hoverable
                  className="catalog-resource-card catalog-memory-card"
                  title={library.name}
                  extra={
                    <ResourceMenu
                      name={library.name}
                      onEdit={canWrite ? () => openEdit(library) : undefined}
                      onDelete={canWrite ? () => handleDelete(library.library_id) : undefined}
                      deleteDescription="规则、记忆节点、用户画像与 Memory Key 将一并清理，此操作不可恢复。"
                    />
                  }
                  actions={[
                    <Button
                      key="detail"
                      type="text"
                      onClick={() => navigate(`/memory/${library.library_id}`)}
                    >
                      打开记忆库
                    </Button>,
                  ]}
                >
                  <Typography.Text className="catalog-resource-card__id" type="secondary">
                    {library.library_id}
                  </Typography.Text>
                  <Typography.Paragraph ellipsis={{ rows: 2 }} type="secondary" style={{ minHeight: 44 }}>
                    {library.description || '暂无描述'}
                  </Typography.Paragraph>
                  <Row gutter={8}>
                    <Col span={8}>
                      <Statistic title="记忆节点" value={library.node_count} valueStyle={{ fontSize: 16 }} />
                    </Col>
                    <Col span={8}>
                      <Statistic
                        title="记忆实体"
                        value={library.entity_count}
                        valueStyle={{ fontSize: 16 }}
                      />
                    </Col>
                    <Col span={8}>
                      <Statistic
                        title="规则数"
                        value={library.fragment_rule_count + library.profile_rule_count}
                        valueStyle={{ fontSize: 16 }}
                      />
                    </Col>
                  </Row>
                </Card>
              </Col>
            ))}
          </Row>
        )}
      </Spin>

      {total > PAGE_SIZE && (
        <div style={{ marginTop: 16, textAlign: 'right' }}>
          <Pagination
            current={page}
            pageSize={PAGE_SIZE}
            total={total}
            showSizeChanger={false}
            onChange={(p) => load(p, keyword)}
          />
        </div>
      )}

      <Modal
        rootClassName="catalog-eval-modal"
        title={editing ? '编辑记忆库' : '新建记忆库'}
        open={upsertOpen}
        onOk={handleSubmit}
        onCancel={() => setUpsertOpen(false)}
        confirmLoading={submitting}
        okText={editing ? '保存' : '创建'}
        cancelText="取消"
        destroyOnHidden
      >
        <Form<MemoryLibraryUpsertRequest> form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入记忆库名称' }]}>
            <Input placeholder="例如：客服助手记忆库" maxLength={64} showCount />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea placeholder="用途说明，便于团队识别（可选）" maxLength={256} rows={3} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

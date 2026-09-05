import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Input, Row, Space, Spin, Tag, Tooltip, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { deleteKnowledgeBase, listKnowledgeBases } from '../../api/kb';
import { getDemoStatus, importDemo } from '../../api/system';
import type { DemoStatus, KnowledgeBase } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import ResourceMenu from '../../components/ResourceMenu';
import PageHeader from '../../components/PageHeader';
import CreateKbModal from './components/CreateKbModal';
import EditKbModal from './components/EditKbModal';

export default function KbListPage() {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [editingKb, setEditingKb] = useState<KnowledgeBase | null>(null);
  const [demoStatus, setDemoStatus] = useState<DemoStatus | null>(null);
  const [demoImporting, setDemoImporting] = useState(false);
  const navigate = useNavigate();
  const { can } = useAuth();
  // Reading a base and changing the set of bases are different rights: kb:read alone gets the list and
  // the detail screens, nothing that creates or removes one. The server enforces this again.
  const canWrite = can(PERMISSIONS.KB_WRITE);

  const loadKbs = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listKnowledgeBases();
      setKbs(data);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadKbs();
  }, [loadKbs]);

  // Drives the empty-state "一键导入 Demo 知识库" button (M3-CONTRACTS.md section 3.7/4).
  useEffect(() => {
    getDemoStatus().then(setDemoStatus);
  }, []);

  const handleDelete = async (kbId: string) => {
    await deleteKnowledgeBase(kbId);
    message.success('知识库已删除');
    loadKbs();
  };

  const handleDemoImport = async () => {
    // Already imported: no need to hit the (idempotent) import API again, just navigate straight in.
    if (demoStatus?.imported && demoStatus.kb_id) {
      navigate(`/kb/${demoStatus.kb_id}`);
      return;
    }
    setDemoImporting(true);
    try {
      const result = await importDemo();
      message.success('Demo 知识库导入成功');
      navigate(`/kb/${result.kb_id}`);
    } finally {
      setDemoImporting(false);
    }
  };

  const visibleResources = kbs.filter((kb) =>
    `${kb.name} ${kb.description ?? ''}`.toLocaleLowerCase().includes(keyword.trim().toLocaleLowerCase()),
  );

  return (
    <div className="knowledge-workbench-page kb-overview-page">
      <PageHeader
        eyebrow="KNOWLEDGE WORKSPACE"
        title="知识库"
        description="集中管理知识资产、解析策略与检索质量，从原始文档一路追踪到可验证的回答。"
        actions={
          canWrite ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
              新建知识库
            </Button>
          ) : undefined
        }
      />

      <div className="catalog-filter-bar">
        <Input
          allowClear
          prefix={<SearchOutlined />}
          aria-label="搜索知识库"
          placeholder="按名称或描述搜索"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <Typography.Text type="secondary">共 {kbs.length} 个知识库</Typography.Text>
      </div>
      <Spin spinning={loading}>
        {!loading && kbs.length === 0 ? (
          <Card className="workbench-empty-card">
            <Empty
              description={
                canWrite
                  ? '还没有知识库。创建后上传文档，即可开始解析、检索与质量评测。'
                  : '当前账号暂无可访问的知识库，如需开通请联系管理员。'
              }
            >
              {canWrite && (
                <Space wrap>
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
                    立即新建
                  </Button>
                  <Tooltip
                    title={demoStatus && !demoStatus.available ? '未找到 Demo 素材目录，暂不可用' : undefined}
                  >
                    <Button
                      icon={<ThunderboltOutlined />}
                      loading={demoImporting}
                      disabled={!demoStatus?.available}
                      onClick={handleDemoImport}
                    >
                      {demoStatus?.imported ? '查看 Demo 知识库' : '导入 Demo 知识库'}
                    </Button>
                  </Tooltip>
                </Space>
              )}
            </Empty>
          </Card>
        ) : (
          <>
            <Row className="knowledge-card-grid" gutter={[18, 18]}>
              {visibleResources.map((kb) => (
                <Col key={kb.kb_id} xs={24} sm={12} xl={8} xxl={6}>
                  <Card
                    hoverable
                    className="knowledge-card"
                    title={kb.name}
                    extra={
                      <ResourceMenu
                        name={kb.name}
                        onEdit={canWrite ? () => setEditingKb(kb) : undefined}
                        onDelete={can(PERMISSIONS.KB_DELETE) ? () => handleDelete(kb.kb_id) : undefined}
                        deleteDescription="删除后其下文档与索引将一并清理，此操作不可恢复。"
                      />
                    }
                    actions={[
                      <Button key="detail" type="text" onClick={() => navigate(`/kb/${kb.kb_id}`)}>
                        打开知识库
                      </Button>,
                    ]}
                  >
                    <Space className="knowledge-card__meta" wrap size={[4, 6]}>
                      <Tag color={kb.graph_enabled ? 'processing' : 'default'}>
                        {kb.graph_enabled ? 'GraphRAG' : '标准检索'}
                      </Tag>
                      {kb.review_required && <Tag color="warning">入库审核</Tag>}
                    </Space>
                    <Typography.Paragraph ellipsis={{ rows: 2 }} type="secondary">
                      {kb.description || '暂无描述'}
                    </Typography.Paragraph>
                  </Card>
                </Col>
              ))}
            </Row>
            {!loading && visibleResources.length === 0 && (
              <Empty description="没有匹配的资源，请调整搜索条件" />
            )}
          </>
        )}
      </Spin>

      <CreateKbModal
        open={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        onCreated={() => {
          setCreateModalOpen(false);
          loadKbs();
        }}
      />

      <EditKbModal
        kb={editingKb}
        onClose={() => setEditingKb(null)}
        onUpdated={() => {
          setEditingKb(null);
          loadKbs();
        }}
      />
    </div>
  );
}

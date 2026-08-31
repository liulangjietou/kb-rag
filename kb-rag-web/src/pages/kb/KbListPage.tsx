import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Popconfirm, Row, Space, Spin, Tag, Tooltip, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { deleteKnowledgeBase, listKnowledgeBases } from '../../api/kb';
import { getDemoStatus, importDemo } from '../../api/system';
import type { DemoStatus, KnowledgeBase } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import PageHeader from '../../components/PageHeader';
import RetrievalPipeline from '../../components/RetrievalPipeline';
import CreateKbModal from './components/CreateKbModal';
import EditKbModal from './components/EditKbModal';

export default function KbListPage() {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
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

  return (
    <div className="knowledge-workbench-page kb-overview-page">
      <PageHeader
        eyebrow="KNOWLEDGE WORKSPACE"
        title="知识库"
        description="集中管理知识资产、解析策略与检索质量，从原始文档一路追踪到可验证的回答。"
        before={<RetrievalPipeline compact />}
        actions={canWrite ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
            新建知识库
          </Button>
        ) : undefined}
      />

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
                  <Tooltip title={demoStatus && !demoStatus.available ? '未找到 Demo 素材目录，暂不可用' : undefined}>
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
          <Row className="knowledge-card-grid" gutter={[18, 18]}>
            {kbs.map((kb) => (
              <Col key={kb.kb_id} xs={24} sm={12} md={8} lg={6}>
                <Card
                  hoverable
                  className="knowledge-card"
                  title={kb.name}
                  actions={[
                    <Button key="detail" type="text" size="small" onClick={() => navigate(`/kb/${kb.kb_id}`)}>
                      查看详情
                    </Button>,
                    ...(canWrite
                      ? [
                          <Button
                            key="edit"
                            type="text"
                            size="small"
                            onClick={() => setEditingKb(kb)}
                          >
                            编辑
                          </Button>,
                          <Popconfirm
                            key="delete"
                            title="确认删除该知识库？"
                            description="删除后其下文档与索引将一并清理，此操作不可恢复"
                            okText="删除"
                            okType="danger"
                            cancelText="取消"
                            onConfirm={() => handleDelete(kb.kb_id)}
                          >
                            <Button type="text" size="small" danger>
                              删除
                            </Button>
                          </Popconfirm>,
                        ]
                      : []),
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

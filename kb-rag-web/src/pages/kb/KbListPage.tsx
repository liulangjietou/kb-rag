import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Popconfirm, Row, Space, Spin, Tooltip, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { deleteKnowledgeBase, listKnowledgeBases } from '../../api/kb';
import { getDemoStatus, importDemo } from '../../api/system';
import type { DemoStatus, KnowledgeBase } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import CreateKbModal from './components/CreateKbModal';

export default function KbListPage() {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  const [createModalOpen, setCreateModalOpen] = useState(false);
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
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          知识库
        </Typography.Title>
        {canWrite && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
            新建知识库
          </Button>
        )}
      </div>

      <Spin spinning={loading}>
        {!loading && kbs.length === 0 ? (
          <Empty
            description={
              canWrite
                ? '还没有知识库，点击右上角「新建知识库」开始创建，上传文档后即可在此进行检索调试'
                : '当前账号暂无可访问的知识库，如需开通请联系管理员'
            }
          >
            {canWrite && (
              <Space>
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
                    {demoStatus?.imported ? '查看 Demo 知识库' : '一键导入 Demo 知识库'}
                  </Button>
                </Tooltip>
              </Space>
            )}
          </Empty>
        ) : (
          <Row gutter={[16, 16]}>
            {kbs.map((kb) => (
              <Col key={kb.kb_id} xs={24} sm={12} md={8} lg={6}>
                <Card
                  hoverable
                  title={kb.name}
                  onClick={() => navigate(`/kb/${kb.kb_id}`)}
                  actions={[
                    <span key="detail" onClick={() => navigate(`/kb/${kb.kb_id}`)}>
                      查看详情
                    </span>,
                    ...(canWrite
                      ? [
                          <Popconfirm
                            key="delete"
                            title="确认删除该知识库？"
                            description="删除后其下文档与索引将一并清理，此操作不可恢复"
                            okText="删除"
                            okType="danger"
                            cancelText="取消"
                            onConfirm={(e) => {
                              e?.stopPropagation();
                              handleDelete(kb.kb_id);
                            }}
                            onCancel={(e) => e?.stopPropagation()}
                          >
                            <span onClick={(e) => e.stopPropagation()}>删除</span>
                          </Popconfirm>,
                        ]
                      : []),
                  ]}
                >
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
    </div>
  );
}

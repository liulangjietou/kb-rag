import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Popconfirm, Row, Spin, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { deleteKnowledgeBase, listKnowledgeBases } from '../../api/kb';
import type { KnowledgeBase } from '../../api/types';
import CreateKbModal from './components/CreateKbModal';

export default function KbListPage() {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const navigate = useNavigate();

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

  const handleDelete = async (kbId: string) => {
    await deleteKnowledgeBase(kbId);
    message.success('知识库已删除');
    loadKbs();
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          知识库
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
          新建知识库
        </Button>
      </div>

      <Spin spinning={loading}>
        {!loading && kbs.length === 0 ? (
          <Empty description="还没有知识库，点击右上角「新建知识库」开始创建，上传文档后即可在此进行检索调试">
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
              立即新建
            </Button>
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

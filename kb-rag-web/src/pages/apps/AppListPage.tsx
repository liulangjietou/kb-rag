// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Popconfirm, Row, Spin, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { deleteApp, listApps } from '../../api/app';
import type { KbApp } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import PageHeader from '../../components/PageHeader';
import CreateAppModal from './components/CreateAppModal';
import EditAppModal from './components/EditAppModal';

/** 应用中心顶级菜单落点：应用列表 + 新建（M4c-CONTRACTS.md section 4）. */
export default function AppListPage() {
  const [apps, setApps] = useState<KbApp[]>([]);
  const [loading, setLoading] = useState(true);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [editingApp, setEditingApp] = useState<KbApp | null>(null);
  const navigate = useNavigate();
  const { can } = useAuth();
  // app:read opens the list and the detail screens; creating or removing an application is app:write.
  const canWrite = can(PERMISSIONS.APP_WRITE);

  const loadApps = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listApps();
      setApps(data);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadApps();
  }, [loadApps]);

  const handleDelete = async (appId: string) => {
    await deleteApp(appId);
    message.success('应用已删除');
    loadApps();
  };

  return (
    <div className="catalog-eval-page catalog-list-page">
      <PageHeader
        eyebrow="APP CATALOG / 应用编排"
        title="应用中心"
        description="将检索策略、知识库与问答模型固化为可发布、可回滚的应用版本。"
        actions={
          canWrite ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
              新建应用
            </Button>
          ) : undefined
        }
      />

      <Spin spinning={loading}>
        {!loading && apps.length === 0 ? (
          <Empty
            description={
              canWrite ? '还没有应用，点击右上角「新建应用」开始创建' : '还没有应用，如需创建请联系管理员'
            }
          >
            {canWrite && (
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
                立即新建
              </Button>
            )}
          </Empty>
        ) : (
          <Row gutter={[16, 16]}>
            {apps.map((app) => (
              <Col key={app.app_id} xs={24} sm={12} md={8} lg={6}>
                <Card
                  hoverable
                  className="catalog-resource-card"
                  title={app.name}
                  actions={[
                    <Button key="detail" type="text" size="small" onClick={() => navigate(`/apps/${app.app_id}`)}>
                      查看详情
                    </Button>,
                    ...(canWrite
                      ? [
                          <Button
                            key="edit"
                            type="text"
                            size="small"
                            onClick={() => setEditingApp(app)}
                          >
                            编辑
                          </Button>,
                          <Popconfirm
                            key="delete"
                            title="确认删除该应用？"
                            description="删除后其下全部版本与配置将一并清理，此操作不可恢复"
                            okText="删除"
                            okType="danger"
                            cancelText="取消"
                            onConfirm={() => handleDelete(app.app_id)}
                          >
                            <Button type="text" size="small" danger>
                              删除
                            </Button>
                          </Popconfirm>,
                        ]
                      : []),
                  ]}
                >
                  <Typography.Text className="catalog-resource-card__id" type="secondary">
                    {app.app_id}
                  </Typography.Text>
                  <Typography.Paragraph ellipsis={{ rows: 2 }} type="secondary">
                    {app.description || '暂无描述'}
                  </Typography.Paragraph>
                </Card>
              </Col>
            ))}
          </Row>
        )}
      </Spin>

      <CreateAppModal
        open={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        onCreated={(app) => {
          setCreateModalOpen(false);
          navigate(`/apps/${app.app_id}`);
        }}
      />

      <EditAppModal
        app={editingApp}
        onClose={() => setEditingApp(null)}
        onUpdated={() => {
          setEditingApp(null);
          loadApps();
        }}
      />
    </div>
  );
}

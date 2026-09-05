// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Input, Row, Spin, Tag, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { deleteApp, listApps } from '../../api/app';
import type { KbApp } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import ResourceMenu from '../../components/ResourceMenu';
import PageHeader from '../../components/PageHeader';
import CreateAppModal from './components/CreateAppModal';
import EditAppModal from './components/EditAppModal';

/** 应用中心顶级菜单落点：应用列表 + 新建（M4c-CONTRACTS.md section 4）. */
export default function AppListPage() {
  const [apps, setApps] = useState<KbApp[]>([]);
  const [keyword, setKeyword] = useState('');
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

  const visibleResources = apps.filter((app) =>
    `${app.name} ${app.description ?? ''}`.toLocaleLowerCase().includes(keyword.trim().toLocaleLowerCase()),
  );

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

      <div className="catalog-filter-bar">
        <Input
          allowClear
          prefix={<SearchOutlined />}
          aria-label="搜索应用"
          placeholder="按名称或描述搜索"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <Typography.Text type="secondary">共 {apps.length} 个应用</Typography.Text>
      </div>
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
          <>
            <Row gutter={[16, 16]}>
              {visibleResources.map((app) => (
                <Col key={app.app_id} xs={24} sm={12} xl={8} xxl={6}>
                  <Card
                    hoverable
                    className="catalog-resource-card"
                    title={app.name}
                    extra={
                      <ResourceMenu
                        name={app.name}
                        onEdit={canWrite ? () => setEditingApp(app) : undefined}
                        onDelete={canWrite ? () => handleDelete(app.app_id) : undefined}
                        deleteDescription="删除后全部版本与配置将一并清理，此操作不可恢复。"
                      />
                    }
                    actions={[
                      <Button key="detail" type="text" onClick={() => navigate(`/apps/${app.app_id}`)}>
                        打开应用
                      </Button>,
                    ]}
                  >
                    <Tag color={app.released_version_id ? 'success' : 'default'}>
                      {app.released_version_id ? `已发布 ${app.released_version ?? ''}` : '尚未发布'}
                    </Tag>
                    <Typography.Paragraph ellipsis={{ rows: 2 }} type="secondary">
                      {app.description || '暂无描述'}
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

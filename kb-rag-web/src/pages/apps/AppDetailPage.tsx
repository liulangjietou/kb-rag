// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Spin, Tabs, Typography } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { getApp, listAppVersions } from '../../api/app';
import { listKnowledgeBases } from '../../api/kb';
import type { AppVersion, KbApp, KnowledgeBase } from '../../api/types';
import PageHeader from '../../components/PageHeader';
import AppConfigTab from './components/AppConfigTab';
import AppVersionTab from './components/AppVersionTab';
import ApiDebugTab from './components/ApiDebugTab';

/**
 * 应用详情页 (M4c-CONTRACTS.md section 4): 配置编辑 + 版本列表（含发布门禁）+ API 调试 三个 tab，
 * all scoped to this app_id.
 */
export default function AppDetailPage() {
  const { appId } = useParams<{ appId: string }>();
  const navigate = useNavigate();
  const [app, setApp] = useState<KbApp | null>(null);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [versions, setVersions] = useState<AppVersion[]>([]);
  const [loading, setLoading] = useState(true);

  const loadApp = useCallback(async () => {
    if (!appId) return;
    const detail = await getApp(appId);
    setApp(detail);
  }, [appId]);

  useEffect(() => {
    if (!appId) return;
    setLoading(true);
    Promise.all([loadApp(), listKnowledgeBases().then(setKbs), listAppVersions(appId).then(setVersions)]).finally(() =>
      setLoading(false),
    );
  }, [appId, loadApp]);

  if (!appId) {
    return null;
  }

  // Newest version pre-fills the config editor; listAppVersions returns newest first (mirrors
  // every other version-list endpoint's ordering convention in this codebase).
  const latestVersion = versions[0] ?? null;

  return (
    <div className="catalog-eval-page catalog-detail-page">
      <PageHeader
        eyebrow="APP WORKSPACE / 应用工作台"
        title={app?.name ?? '应用详情'}
        description={app?.description || '编辑应用配置、管理版本发布门禁，并通过真实 API 请求验证效果。'}
        before={
          <Button
            type="text"
            className="catalog-back-button"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/apps')}
          >
            返回应用中心
          </Button>
        }
        actions={app ? <Typography.Text className="catalog-context-id">{app.app_id}</Typography.Text> : undefined}
      />

      <Spin spinning={loading}>
        <Tabs
          className="catalog-workbench-tabs"
          items={[
            {
              key: 'config',
              label: '配置编辑',
              children: (
                <AppConfigTab
                  appId={appId}
                  kbs={kbs}
                  latestVersion={latestVersion}
                  onVersionCreated={() => listAppVersions(appId).then(setVersions)}
                />
              ),
            },
            {
              key: 'versions',
              label: '版本列表',
              children: <AppVersionTab appId={appId} kbs={kbs} onVersionsChanged={setVersions} />,
            },
            {
              key: 'api-debug',
              label: 'API 调试',
              children: <ApiDebugTab appId={appId} kbs={kbs} />,
            },
          ]}
        />
      </Spin>
    </div>
  );
}

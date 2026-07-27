// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Space, Spin, Tabs, Typography } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { getApp, listAppVersions } from '../../api/app';
import { listKnowledgeBases } from '../../api/kb';
import type { AppVersion, KbApp, KnowledgeBase } from '../../api/types';
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
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/apps')}>
          返回列表
        </Button>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {app?.name ?? '应用详情'}
        </Typography.Title>
      </Space>
      {app?.description && <Typography.Paragraph type="secondary">{app.description}</Typography.Paragraph>}

      <Spin spinning={loading}>
        <Tabs
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

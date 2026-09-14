// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { Alert, Button, Spin, Tabs, Typography } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { getApp, listAppVersions } from '../../api/app';
import { listKnowledgeBases } from '../../api/kb';
import type { AppVersion, KbApp, KnowledgeBase } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import { useResourceVisit } from '../../hooks/useResourceVisit';
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
  const { token, can } = useAuth();
  if (!appId || !can(PERMISSIONS.APP_READ)) return null;
  const scope = [token, appId, can(PERMISSIONS.KB_READ), can(PERMISSIONS.APP_WRITE),
    can(PERMISSIONS.APP_RELEASE), can(PERMISSIONS.EVAL_READ)].join(':');
  return <ScopedAppDetailPage key={scope} appId={appId} />;
}

/** 详情和编辑器以当前应用、登录身份与权限为同一生命周期。 */
function ScopedAppDetailPage({ appId }: { appId: string }) {
  const navigate = useNavigate();
  const { can } = useAuth();
  const canReadKb = can(PERMISSIONS.KB_READ);
  const [app, setApp] = useState<KbApp | null>(null);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [versions, setVersions] = useState<AppVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const alive = useRef(true);
  const loadSequence = useRef(0);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; loadSequence.current += 1; };
  }, []);

  useResourceVisit('APP', appId, Boolean(app?.app_id === appId && !loading));

  const loadApp = useCallback(async () => {
    const request = ++loadSequence.current;
    setLoading(true);
    setLoadError(false);
    setApp(null);
    setKbs([]);
    setVersions([]);
    try {
      const [detail, bases, rows] = await Promise.all([
        getApp(appId), canReadKb ? listKnowledgeBases() : Promise.resolve([]), listAppVersions(appId),
      ]);
      if (!alive.current || request !== loadSequence.current) return;
      setApp(detail);
      setKbs(bases);
      setVersions(rows);
    } catch {
      if (alive.current && request === loadSequence.current) setLoadError(true);
    } finally {
      if (alive.current && request === loadSequence.current) setLoading(false);
    }
  }, [appId, canReadKb]);

  const refreshVersions = async () => {
    const request = ++loadSequence.current;
    try {
      const rows = await listAppVersions(appId);
      if (alive.current && request === loadSequence.current) setVersions(rows);
    } catch {
      if (alive.current && request === loadSequence.current) {
        setVersions([]);
        setLoadError(true);
      }
    }
  };

  useEffect(() => {
    void loadApp();
  }, [loadApp]);

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
        {loadError && <Alert type="error" showIcon message="应用详情加载失败" description="请重试后再查看配置和版本。"
          action={<Button onClick={() => void loadApp()}>重试</Button>} />}
        {app && !loading && !loadError && <Tabs
          className="catalog-workbench-tabs"
          items={[
            {
              key: 'config',
              label: '应用配置',
              children: !loading && (
                <AppConfigTab
                  appId={appId}
                  kbs={kbs}
                  latestVersion={latestVersion}
                  onVersionCreated={refreshVersions}
                />
              ),
            },
            {
              key: 'versions',
              label: '版本与发布',
              children: <AppVersionTab appId={appId} kbs={kbs} onVersionsChanged={setVersions} />,
            },
            {
              key: 'api-debug',
              label: 'API 调试',
              children: <ApiDebugTab appId={appId} kbs={kbs} />,
            },
          ]}
        />}
      </Spin>
    </div>
  );
}

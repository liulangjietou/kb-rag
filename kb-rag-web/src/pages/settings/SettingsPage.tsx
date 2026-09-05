import { Card, Grid, Tabs, Typography } from 'antd';
import { useModelStatus } from '../../context/ModelStatusContext';
import PageHeader from '../../components/PageHeader';
import AlertConfigTab from './components/AlertConfigTab';
import ApiKeyTab from './components/ApiKeyTab';
import AuditLogTab from './components/AuditLogTab';
import IkDictTab from './components/IkDictTab';
import ModelStatusCards from './components/ModelStatusCards';
import SourceMappingTab from './components/SourceMappingTab';
import WebCredentialTab from './components/WebCredentialTab';

/**
 * System settings page (M2-CONTRACTS.md section 5): model status tab upgraded from the M1
 * placeholder to embedding/rerank/chat three-card layout, plus a new ik dictionary management tab.
 */
export default function SettingsPage() {
  const { modelStatus, loading } = useModelStatus();
  const screens = Grid.useBreakpoint();

  return (
    <div className="management-page settings-page">
      <PageHeader
        eyebrow="PLATFORM CONFIGURATION"
        title="系统设置"
        description="维护模型配置、检索词典、访问凭据与运行告警。"
      />
      <Tabs
        className="settings-tabs"
        tabPosition={screens.lg === false ? 'top' : 'left'}
        items={[
          {
            key: 'model-status',
            label: <span className="settings-nav-label"><small>模型与检索</small>模型配置</span>,
            children: (
              <Card className="settings-panel" loading={loading}>
                {modelStatus ? (
                  <ModelStatusCards
                    vectorEngine={modelStatus.vector_engine}
                    embedding={{ configured: modelStatus.embedding_configured, provider: modelStatus.provider, model: modelStatus.model }}
                    rerank={{ configured: modelStatus.rerank_configured, provider: modelStatus.rerank_provider, model: modelStatus.rerank_model }}
                    chat={{ configured: modelStatus.chat_configured, provider: modelStatus.chat_provider, model: modelStatus.chat_model }}
                    vision={{ configured: modelStatus.vision_configured, provider: modelStatus.vision_provider, model: modelStatus.vision_model }}
                  />
                ) : (
                  <Typography.Text type="secondary">正在加载模型状态...</Typography.Text>
                )}
              </Card>
            ),
          },
          {
            key: 'ik-dict',
            label: '检索词典',
            children: (
              <Card className="settings-panel">
                <IkDictTab />
              </Card>
            ),
          },
          {
            key: 'api-key',
            label: <span className="settings-nav-label"><small>访问与集成</small>API Key</span>,
            children: (
              <Card className="settings-panel">
                <ApiKeyTab />
              </Card>
            ),
          },
          {
            key: 'web-credential',
            label: '站点凭据',
            children: (
              <Card className="settings-panel">
                <WebCredentialTab />
              </Card>
            ),
          },
          {
            key: 'source-mapping',
            label: '导入映射',
            children: (
              <Card className="settings-panel">
                <SourceMappingTab />
              </Card>
            ),
          },
          {
            key: 'alert',
            label: <span className="settings-nav-label"><small>运行管理</small>告警配置</span>,
            children: (
              <Card className="settings-panel">
                <AlertConfigTab />
              </Card>
            ),
          },
          {
            key: 'audit-log',
            label: 'API 调用日志',
            children: (
              <Card className="settings-panel">
                <AuditLogTab />
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
}

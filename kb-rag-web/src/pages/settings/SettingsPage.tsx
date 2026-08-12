import { Card, Tabs, Typography } from 'antd';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import { useModelStatus } from '../../context/ModelStatusContext';
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
  // ik 词典与告警出口是部署级设施，不是租户自己的配置：词典由 ES 集群按一个 URL 拉取、全部署共用
  // 一份分词结果，告警 webhook 是运维出口。V23 起它们要 platform:config，而那个码只有默认租户的
  // 超管持有。这里把两个页签藏起来只是不让人白点一下，服务端注解才是判定处。
  const platformOperator = useAuth().can(PERMISSIONS.PLATFORM_CONFIG);

  return (
    <div>
      <Typography.Title level={4}>系统设置</Typography.Title>
      <Tabs
        items={[
          {
            key: 'model-status',
            label: '模型状态',
            children: (
              <Card loading={loading}>
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
          ...(platformOperator
            ? [
                {
                  key: 'ik-dict',
                  label: 'ik 词典',
                  children: (
                    <Card>
                      <IkDictTab />
                    </Card>
                  ),
                },
                {
                  key: 'alert',
                  label: '告警',
                  children: (
                    <Card>
                      <AlertConfigTab />
                    </Card>
                  ),
                },
              ]
            : []),
          {
            key: 'api-key',
            label: 'API Key 管理',
            children: (
              <Card>
                <ApiKeyTab />
              </Card>
            ),
          },
          {
            key: 'audit-log',
            label: '审计日志查询',
            children: (
              <Card>
                <AuditLogTab />
              </Card>
            ),
          },
          {
            key: 'source-mapping',
            label: '导入映射',
            children: (
              <Card>
                <SourceMappingTab />
              </Card>
            ),
          },
          {
            key: 'web-credential',
            label: '站点凭据',
            children: (
              <Card>
                <WebCredentialTab />
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
}

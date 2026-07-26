import { Card, Tabs, Typography } from 'antd';
import { useModelStatus } from '../../context/ModelStatusContext';
import AlertConfigTab from './components/AlertConfigTab';
import IkDictTab from './components/IkDictTab';
import ModelStatusCards from './components/ModelStatusCards';

/**
 * System settings page (M2-CONTRACTS.md section 5): model status tab upgraded from the M1
 * placeholder to embedding/rerank/chat three-card layout, plus a new ik dictionary management tab.
 */
export default function SettingsPage() {
  const { modelStatus, loading } = useModelStatus();

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
        ]}
      />
    </div>
  );
}

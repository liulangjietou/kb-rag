import { Card, Tabs, Typography } from 'antd';
import { useModelStatus } from '../../context/ModelStatusContext';
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
                    embedding={modelStatus.embedding}
                    rerank={modelStatus.rerank}
                    chat={modelStatus.chat}
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
        ]}
      />
    </div>
  );
}

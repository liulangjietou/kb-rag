import { Card, Descriptions, Tag, Typography } from 'antd';
import { useModelStatus } from '../../context/ModelStatusContext';

/**
 * Placeholder system settings page for M1 (M1-CONTRACTS.md section 7 "系统设置占位页").
 * Shows read-only model status; full configuration management lands in a later milestone.
 */
export default function SettingsPage() {
  const { modelStatus, loading } = useModelStatus();

  return (
    <div>
      <Typography.Title level={4}>系统设置</Typography.Title>
      <Card title="嵌入模型状态" loading={loading}>
        {modelStatus ? (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="嵌入模型是否配置">
              {modelStatus.embedding_configured ? (
                <Tag color="success">已配置</Tag>
              ) : (
                <Tag color="warning">未配置（BM25 单路检索模式）</Tag>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="向量引擎">{modelStatus.vector_engine}</Descriptions.Item>
            <Descriptions.Item label="模型供应商">
              <Typography.Text type={modelStatus.provider ? undefined : 'secondary'}>
                {modelStatus.provider ?? '未配置'}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="模型名称">
              <Typography.Text type={modelStatus.model ? undefined : 'secondary'}>
                {modelStatus.model ?? '未配置'}
              </Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        ) : (
          <Typography.Text type="secondary">正在加载模型状态...</Typography.Text>
        )}
        {modelStatus && !modelStatus.embedding_configured && (
          <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
            提示：在部署环境的 .env 中配置 DASHSCOPE_API_KEY 后重启服务，即可启用向量检索双路能力。
          </Typography.Paragraph>
        )}
      </Card>
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        更多系统设置能力（用户管理、索引管理等）将在后续里程碑中提供。
      </Typography.Paragraph>
    </div>
  );
}

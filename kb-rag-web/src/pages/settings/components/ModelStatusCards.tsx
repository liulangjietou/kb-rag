import { Card, Col, Row, Tag, Typography } from 'antd';
import type { ModelProviderView } from '../../../api/types';

interface ModelStatusCardsProps {
  vectorEngine: string;
  embedding: ModelProviderView;
  rerank: ModelProviderView;
  chat: ModelProviderView;
  /** M3-CONTRACTS.md section 3.1: qwen-vl-max image understanding model status. */
  vision: ModelProviderView;
}

interface CardSpec {
  key: string;
  title: string;
  status: ModelProviderView;
  unconfiguredHint: string;
}

/** One embedding/rerank/chat model status card (M2-CONTRACTS.md section 5 "模型状态卡片"). */
function ModelCard({ title, status, unconfiguredHint }: Omit<CardSpec, 'key'>) {
  return (
    <Card className={`model-status-card${status.configured ? ' is-ready' : ' is-missing'}`} title={title} size="small">
      <Typography.Paragraph style={{ marginBottom: 8 }}>
        {status.configured ? <Tag color="success">已配置</Tag> : <Tag color="warning">未配置</Tag>}
      </Typography.Paragraph>
      <Typography.Paragraph style={{ marginBottom: 4 }} type="secondary">
        供应商：{status.provider ?? '未配置'}
      </Typography.Paragraph>
      <Typography.Paragraph style={{ marginBottom: 0 }} type="secondary">
        模型：{status.model ?? '未配置'}
      </Typography.Paragraph>
      {!status.configured && (
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          {unconfiguredHint}
        </Typography.Paragraph>
      )}
    </Card>
  );
}

/** embedding/rerank/chat/vision 四卡 model status row, driven by the extended GET /system/model-status. */
export default function ModelStatusCards({ vectorEngine, embedding, rerank, chat, vision }: ModelStatusCardsProps) {
  const cards: CardSpec[] = [
    {
      key: 'embedding',
      title: '嵌入模型（embedding）',
      status: embedding,
      unconfiguredHint: '未配置时检索走 BM25 单路，degraded 含 vector_route_unavailable',
    },
    {
      key: 'rerank',
      title: '重排模型（rerank）',
      status: rerank,
      unconfiguredHint: '未配置时 rerank_enabled 自动关闭，检索走融合排序结果',
    },
    {
      key: 'chat',
      title: '对话模型（chat）',
      status: chat,
      unconfiguredHint: '未配置时查询改写自动关闭，检索使用原始 query',
    },
    {
      key: 'vision',
      title: '视觉模型（vision）',
      status: vision,
      unconfiguredHint: '未配置或调用失败时图片跳过处理，文档其余部分正常入库',
    },
  ];

  return (
    <>
      <Typography.Paragraph className="model-engine-label" type="secondary">
        向量引擎：{vectorEngine}
      </Typography.Paragraph>
      <Row gutter={[16, 16]}>
        {cards.map((card) => (
          <Col key={card.key} xs={24} sm={12} xl={6}>
            <ModelCard title={card.title} status={card.status} unconfiguredHint={card.unconfiguredHint} />
          </Col>
        ))}
      </Row>
    </>
  );
}

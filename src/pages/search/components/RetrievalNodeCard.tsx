import { Card, Space, Tag, Typography } from 'antd';
import type { RetrievalNode } from '../../../api/types';
import { formatScore } from '../../../utils/format';
import { RETRIEVAL_SOURCE_META, SCORE_TYPE_META } from '../../../utils/statusMeta';

interface RetrievalNodeCardProps {
  node: RetrievalNode;
  rank: number;
}

/** One retrieval result card: content + score/score_type/retrieval_source tags (M1-CONTRACTS.md section 5). */
export default function RetrievalNodeCard({ node, rank }: RetrievalNodeCardProps) {
  const scoreTypeMeta = SCORE_TYPE_META[node.score_type];
  const sourceMeta = RETRIEVAL_SOURCE_META[node.retrieval_source];

  return (
    <Card size="small" style={{ marginBottom: 12 }}>
      <Space wrap style={{ marginBottom: 8 }}>
        <Tag>#{rank}</Tag>
        <Tag color="blue">score: {formatScore(node.score)}</Tag>
        <Tag color={scoreTypeMeta.color}>{scoreTypeMeta.label}</Tag>
        <Tag color={sourceMeta.color}>{sourceMeta.label}</Tag>
        <Tag>{node.chunk_type}</Tag>
      </Space>
      <Typography.Paragraph
        style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}
        ellipsis={{ rows: 4, expandable: true, symbol: '展开全文' }}
      >
        {node.content}
      </Typography.Paragraph>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        doc_id: {node.doc_id} · chunk_id: {node.chunk_id}
      </Typography.Text>
    </Card>
  );
}

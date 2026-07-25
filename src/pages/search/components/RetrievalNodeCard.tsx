import { Card, Collapse, List, Space, Tag, Typography } from 'antd';
import type { RetrievalChildHit, RetrievalNode } from '../../../api/types';
import { formatScore } from '../../../utils/format';
import { RETRIEVAL_SOURCE_META, SCORE_TYPE_META } from '../../../utils/statusMeta';

interface RetrievalNodeCardProps {
  node: RetrievalNode;
  rank: number;
  /** Shared "threshold applied on" tag resolved once per search response (see AppliedInfoBar). */
  thresholdTag: { color: string; label: string } | null;
}

/** metadata keys shown as "各路原始分/归一化分/fused 分/rerank 分" tags, in display order. */
const NODE_SCORE_FIELDS: Array<{ key: keyof RetrievalChildHit; label: string }> = [
  { key: 'vector_score', label: '向量原始分' },
  { key: 'bm25_score', label: 'BM25原始分' },
  { key: 'norm_vector_score', label: '向量归一化分' },
  { key: 'norm_bm25_score', label: 'BM25归一化分' },
  { key: 'fused_score', label: '融合分' },
  { key: 'rerank_score', label: '重排分' },
];

/** Renders the per-route score tags that are present on a node/child (M2-CONTRACTS.md section 5). */
function RouteScoreTags({ source }: { source: Record<string, unknown> }) {
  const tags = NODE_SCORE_FIELDS.filter(({ key }) => typeof source[key] === 'number');
  if (tags.length === 0) {
    return null;
  }
  return (
    <Space wrap>
      {tags.map(({ key, label }) => (
        <Tag key={key}>
          {label}: {formatScore(source[key] as number)}
        </Tag>
      ))}
    </Space>
  );
}

/** One retrieval result card: content + score/score_type/retrieval_source tags (M1-CONTRACTS.md
 * section 5), extended with M2's per-route/normalized/fused/rerank scores, the threshold-applied
 * tag, and (parent/child mode) an expandable list of the child chunks merged into this node. */
export default function RetrievalNodeCard({ node, rank, thresholdTag }: RetrievalNodeCardProps) {
  const scoreTypeMeta = SCORE_TYPE_META[node.score_type];
  const sourceMeta = RETRIEVAL_SOURCE_META[node.retrieval_source];
  const metadata = node.metadata;
  const children = metadata?.children ?? [];

  return (
    <Card size="small" style={{ marginBottom: 12 }}>
      <Space wrap style={{ marginBottom: 8 }}>
        <Tag>#{rank}</Tag>
        <Tag color="blue">score: {formatScore(node.score)}</Tag>
        <Tag color={scoreTypeMeta.color}>{scoreTypeMeta.label}</Tag>
        <Tag color={sourceMeta.color}>{sourceMeta.label}</Tag>
        <Tag>{node.chunk_type}</Tag>
        {thresholdTag && <Tag color={thresholdTag.color}>{thresholdTag.label}</Tag>}
      </Space>
      {metadata && (
        <div style={{ marginBottom: 8 }}>
          <RouteScoreTags source={metadata} />
        </div>
      )}
      <Typography.Paragraph
        style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}
        ellipsis={{ rows: 4, expandable: true, symbol: '展开全文' }}
      >
        {node.content}
      </Typography.Paragraph>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        doc_id: {node.doc_id} · chunk_id: {node.chunk_id}
      </Typography.Text>
      {children.length > 0 && (
        <Collapse
          size="small"
          style={{ marginTop: 8 }}
          items={[
            {
              key: 'children',
              label: `展开命中子片明细（${children.length}）`,
              children: (
                <List
                  size="small"
                  dataSource={children}
                  renderItem={(child) => (
                    <List.Item key={child.chunk_id}>
                      <Space direction="vertical" style={{ width: '100%' }} size={4}>
                        <Space wrap>
                          <Tag>{child.chunk_id}</Tag>
                          <Tag color={SCORE_TYPE_META[child.score_type].color}>
                            {SCORE_TYPE_META[child.score_type].label}: {formatScore(child.score)}
                          </Tag>
                        </Space>
                        <RouteScoreTags source={child} />
                        <Typography.Paragraph
                          style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
                          ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}
                        >
                          {child.content}
                        </Typography.Paragraph>
                      </Space>
                    </List.Item>
                  )}
                />
              ),
            },
          ]}
        />
      )}
    </Card>
  );
}

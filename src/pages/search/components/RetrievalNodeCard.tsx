import { Card, Checkbox, Collapse, Image, List, Space, Tag, Typography } from 'antd';
import type { RetrievalChildHit, RetrievalNode } from '../../../api/types';
import { formatEpochMillis, formatScore } from '../../../utils/format';
import { CHUNK_TYPE_META, RETRIEVAL_SOURCE_META, SCORE_TYPE_META, metaOf } from '../../../utils/statusMeta';

interface RetrievalNodeCardProps {
  node: RetrievalNode;
  rank: number;
  /** Shared "threshold applied on" tag resolved once per search response (see AppliedInfoBar). */
  thresholdTag: { color: string; label: string } | null;
  /**
   * M4b-CONTRACTS.md section 5 "收进评测集": when set, renders a selection checkbox so the page
   * can collect a subset of result cards into an eval-dataset case (POST cases/from-retrieval).
   */
  selected?: boolean;
  onSelectChange?: (checked: boolean) => void;
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
export default function RetrievalNodeCard({ node, rank, thresholdTag, selected, onSelectChange }: RetrievalNodeCardProps) {
  const scoreTypeMeta = metaOf(SCORE_TYPE_META, node.score_type);
  const sourceMeta = metaOf(RETRIEVAL_SOURCE_META, node.retrieval_source);
  const chunkTypeMeta = metaOf(CHUNK_TYPE_META, node.chunk_type);
  const metadata = node.metadata;
  const children = metadata?.children ?? [];
  const isChatLog = node.chunk_type === 'chat_log';

  return (
    <Card size="small" style={{ marginBottom: 12 }}>
      <Space wrap style={{ marginBottom: 8 }}>
        {onSelectChange && (
          <Checkbox checked={!!selected} onChange={(e) => onSelectChange(e.target.checked)} />
        )}
        <Tag>#{rank}</Tag>
        <Tag color="blue">score: {formatScore(node.score)}</Tag>
        <Tag color={scoreTypeMeta.color}>{scoreTypeMeta.label}</Tag>
        <Tag color={sourceMeta.color}>{sourceMeta.label}</Tag>
        <Tag color={chunkTypeMeta.color}>{chunkTypeMeta.label}</Tag>
        {thresholdTag && <Tag color={thresholdTag.color}>{thresholdTag.label}</Tag>}
      </Space>
      {isChatLog && metadata && (
        <Space wrap style={{ marginBottom: 8 }}>
          {metadata.session_name && <Tag>会话：{metadata.session_name}</Tag>}
          {metadata.sender && <Tag>发送人：{metadata.sender}</Tag>}
          {metadata.msg_time !== undefined && <Tag>{formatEpochMillis(metadata.msg_time)}</Tag>}
          {/* M8-CONTRACTS.md section 0.5: overlap window sequence + message span, chat_log-only. */}
          {metadata.window_seq !== undefined && <Tag>窗口序号：{metadata.window_seq}</Tag>}
          {metadata.msg_span && (
            <Tag>
              消息区间：[{metadata.msg_span[0]}, {metadata.msg_span[1]}]
            </Tag>
          )}
        </Space>
      )}
      {/*
        M8-CONTRACTS.md section 0.6: near-duplicate overlapping-window merge -- surfaced the same
        way as the graph-route paragraph below, only when the server actually folded lower-ranked
        overlapping windows into this node.
      */}
      {metadata?.merged_window_chunk_ids && metadata.merged_window_chunk_ids.length > 0 && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          已归并重叠窗口 ×{metadata.merged_window_chunk_ids.length}（{metadata.merged_window_chunk_ids.join('、')}）
        </Typography.Paragraph>
      )}
      {metadata?.graph_score !== undefined && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          图路：关联度 {formatScore(metadata.graph_score)} / 跳数 {metadata.graph_hops ?? '-'} / 实体{' '}
          {metadata.graph_entities && metadata.graph_entities.length > 0 ? metadata.graph_entities.join('、') : '-'}
        </Typography.Paragraph>
      )}
      {node.image_urls.length > 0 && (
        <div style={{ marginBottom: 8 }}>
          <Image.PreviewGroup>
            <Space wrap>
              {node.image_urls.map((url) => (
                <Image key={url} src={url} width={96} height={96} style={{ objectFit: 'cover' }} />
              ))}
            </Space>
          </Image.PreviewGroup>
        </div>
      )}
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
                          <Tag color={metaOf(SCORE_TYPE_META, child.score_type).color}>
                            {metaOf(SCORE_TYPE_META, child.score_type).label}: {formatScore(child.score)}
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

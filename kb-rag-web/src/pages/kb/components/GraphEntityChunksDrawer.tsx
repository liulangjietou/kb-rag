// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { Drawer, Empty, List, Space, Spin, Tag, Typography } from 'antd';
import { getGraphEntityChunks } from '../../../api/graph';
import type { GraphEntitySourceChunk } from '../../../api/types';

interface GraphEntityChunksDrawerProps {
  kbId: string;
  /** Entity name being drilled into; drawer is closed when null. */
  entityName: string | null;
  onClose: () => void;
}

/**
 * Entity source-chunk drill-down drawer (M7-CONTRACTS.md section 2: "点击实体下钻来源分片抽屉
 * （含所属文档版本）"). One row per (:Entity)-[:MENTIONED_IN]->(:Chunk) edge resolved back to its
 * MySQL chunk row; see GraphEntitySourceChunk's doc comment in api/types.ts for the simplified
 * row shape assumption.
 */
export default function GraphEntityChunksDrawer({ kbId, entityName, onClose }: GraphEntityChunksDrawerProps) {
  const [chunks, setChunks] = useState<GraphEntitySourceChunk[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!entityName) {
      setChunks([]);
      return;
    }
    setLoading(true);
    getGraphEntityChunks(kbId, entityName)
      .then(setChunks)
      .finally(() => setLoading(false));
  }, [kbId, entityName]);

  return (
    <Drawer title={`来源分片${entityName ? ` - ${entityName}` : ''}`} open={entityName !== null} onClose={onClose} width={640} destroyOnHidden>
      <Spin spinning={loading}>
        {chunks.length === 0 && !loading ? (
          <Empty description="暂无来源分片" />
        ) : (
          <List
            dataSource={chunks}
            renderItem={(chunk) => (
              <List.Item key={chunk.chunk_id} style={{ opacity: chunk.enabled ? 1 : 0.55 }}>
                <Space direction="vertical" style={{ width: '100%' }} size={4}>
                  <Space wrap>
                    <Tag>{chunk.doc_file_name}</Tag>
                    <Tag color="blue">版本 {chunk.document_version_label}</Tag>
                    {!chunk.enabled && <Tag color="default">已禁用</Tag>}
                  </Space>
                  <Typography.Paragraph
                    style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
                    ellipsis={{ rows: 4, expandable: true, symbol: '展开全文' }}
                  >
                    {chunk.content}
                  </Typography.Paragraph>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    chunk_id: {chunk.chunk_id}
                  </Typography.Text>
                </Space>
              </List.Item>
            )}
          />
        )}
      </Spin>
    </Drawer>
  );
}

import { useEffect, useState } from 'react';
import { Descriptions, Drawer, Empty, List, Pagination, Space, Spin, Tag, Typography } from 'antd';
import { listChunks } from '../../../api/document';
import type { KbChunk } from '../../../api/types';
import { EMBEDDING_STATUS_META } from '../../../utils/statusMeta';

interface ChunkDrawerProps {
  /** The document whose chunks are being inspected; drawer is closed when null. */
  docId: string | null;
  docName: string | null;
  onClose: () => void;
}

const DEFAULT_PAGE = 1;

/** Paginated chunk viewer opened from a row in the document table (M1-CONTRACTS.md section 7). */
export default function ChunkDrawer({ docId, docName, onClose }: ChunkDrawerProps) {
  const [chunks, setChunks] = useState<KbChunk[]>([]);
  const [total, setTotal] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(DEFAULT_PAGE);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!docId) {
      return;
    }
    setLoading(true);
    listChunks(docId, page)
      .then((result) => {
        setChunks(result.items);
        setTotal(result.total);
        setPageSize(result.size || 10);
      })
      .finally(() => setLoading(false));
  }, [docId, page]);

  useEffect(() => {
    // Reset to page 1 whenever a different document is opened.
    if (docId) {
      setPage(DEFAULT_PAGE);
    }
  }, [docId]);

  return (
    <Drawer
      title={`分片详情${docName ? ` - ${docName}` : ''}`}
      open={docId !== null}
      onClose={onClose}
      width={640}
      destroyOnClose
    >
      <Spin spinning={loading}>
        {chunks.length === 0 && !loading ? (
          <Empty description="暂无分片，文档可能仍在处理中" />
        ) : (
          <List
            itemLayout="vertical"
            dataSource={chunks}
            renderItem={(chunk) => (
              <List.Item key={chunk.chunk_id}>
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Space wrap>
                    <Tag>seq: {chunk.seq}</Tag>
                    <Tag color={chunk.enabled ? 'success' : 'default'}>
                      {chunk.enabled ? '已启用' : '已停用'}
                    </Tag>
                    <Tag color={EMBEDDING_STATUS_META[chunk.embedding_status].color}>
                      {EMBEDDING_STATUS_META[chunk.embedding_status].label}
                    </Tag>
                  </Space>
                  <Typography.Paragraph
                    style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}
                    ellipsis={{ rows: 6, expandable: true, symbol: '展开全文' }}
                  >
                    {chunk.content}
                  </Typography.Paragraph>
                  {chunk.metadata && Object.keys(chunk.metadata).length > 0 && (
                    <Descriptions size="small" column={1} bordered>
                      {Object.entries(chunk.metadata).map(([key, value]) => (
                        <Descriptions.Item key={key} label={key}>
                          {String(value)}
                        </Descriptions.Item>
                      ))}
                    </Descriptions>
                  )}
                </Space>
              </List.Item>
            )}
          />
        )}
        {total > 0 && (
          <Pagination
            style={{ marginTop: 16, textAlign: 'right' }}
            current={page}
            pageSize={pageSize}
            total={total}
            onChange={setPage}
            showSizeChanger={false}
          />
        )}
      </Spin>
    </Drawer>
  );
}

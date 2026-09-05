import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Descriptions,
  Drawer,
  Empty,
  Input,
  List,
  Modal,
  Pagination,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd';
import { editChunk, mergeChunks, splitChunk, toggleChunk } from '../../../api/annotation';
import { listChunks } from '../../../api/document';
import type { KbChunk } from '../../../api/types';
import { EMBEDDING_STATUS_META, metaOf } from '../../../utils/statusMeta';

const { TextArea } = Input;

interface ChunkDrawerProps {
  /** The document whose chunks are being inspected; drawer is closed when null. */
  docId: string | null;
  docName: string | null;
  onClose: () => void;
}

const DEFAULT_PAGE = 1;

/**
 * Chunk annotation workbench (M4a-CONTRACTS.md sections 2.1/2.2/3, upgraded from the M1 read-only
 * chunk viewer): inline content edit + save, enable/disable toggle (disabled chunks render dimmed),
 * multi-select merge, and single-chunk split (either by clicking positions in the read-only body
 * preview or by typing a comma-separated offset list). Every mutation writes exactly one
 * t_kb_annotation row server-side; this drawer just re-fetches the current page afterwards rather
 * than trying to patch local state from the mutation response.
 */
export default function ChunkDrawer({ docId, docName, onClose }: ChunkDrawerProps) {
  const { can } = useAuth();
  const canWrite = can(PERMISSIONS.DOC_WRITE);
  const [chunks, setChunks] = useState<KbChunk[]>([]);
  const [total, setTotal] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(DEFAULT_PAGE);
  const [loading, setLoading] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingContent, setEditingContent] = useState('');
  const [savingId, setSavingId] = useState<string | null>(null);
  const [merging, setMerging] = useState(false);
  const [splitTarget, setSplitTarget] = useState<KbChunk | null>(null);
  const [splitOffsets, setSplitOffsets] = useState<number[]>([]);
  const [splitManualInput, setSplitManualInput] = useState('');
  const [splitting, setSplitting] = useState(false);

  const loadChunks = useCallback(() => {
    if (!docId) return Promise.resolve();
    setLoading(true);
    return listChunks(docId, page)
      .then((result) => {
        setChunks(result.items);
        setTotal(result.total);
        setPageSize(result.size || 10);
      })
      .finally(() => setLoading(false));
  }, [docId, page]);

  useEffect(() => {
    loadChunks();
  }, [loadChunks]);

  useEffect(() => {
    // Reset to page 1 whenever a different document is opened.
    if (docId) {
      setPage(DEFAULT_PAGE);
    }
  }, [docId]);

  useEffect(() => {
    // Selection/edit state can only ever reference rows on the currently loaded page.
    setSelectedIds([]);
    setEditingId(null);
  }, [docId, page]);


  const handleToggle = async (chunk: KbChunk, enabled: boolean) => {
    setSavingId(chunk.chunk_id);
    try {
      await toggleChunk(chunk.chunk_id, { enabled });
      message.success(enabled ? '已启用分片' : '已禁用分片');
      await loadChunks();
    } finally {
      setSavingId(null);
    }
  };

  const startEdit = (chunk: KbChunk) => {
    setEditingId(chunk.chunk_id);
    setEditingContent(chunk.content);
  };

  const cancelEdit = () => {
    setEditingId(null);
    setEditingContent('');
  };

  const handleSaveEdit = async (chunk: KbChunk) => {
    if (!editingContent.trim()) {
      message.warning('分片正文不能为空');
      return;
    }
    setSavingId(chunk.chunk_id);
    try {
      await editChunk(chunk.chunk_id, { content: editingContent });
      message.success('分片正文已保存，已触发重嵌入');
      setEditingId(null);
      await loadChunks();
    } finally {
      setSavingId(null);
    }
  };

  const toggleSelected = (chunkId: string, checked: boolean) => {
    setSelectedIds((prev) => (checked ? [...prev, chunkId] : prev.filter((id) => id !== chunkId)));
  };

  const handleMerge = async () => {
    const targets = chunks.filter((c) => selectedIds.includes(c.chunk_id)).sort((a, b) => a.seq - b.seq);
    if (targets.length < 2) {
      message.warning('请至少勾选 2 个分片');
      return;
    }
    // Client-side pre-check mirroring the server's fast-fail rule (M4a-CONTRACTS.md section 2.1):
    // same parent_id and contiguous seq. The server remains the source of truth for validation.
    const sameParent = targets.every((c) => c.parent_id === targets[0].parent_id);
    const contiguous = targets.every((c, i) => i === 0 || c.seq === targets[i - 1].seq + 1);
    if (!sameParent || !contiguous) {
      message.warning('仅能合并同一父片下 seq 连续的分片');
      return;
    }
    setMerging(true);
    try {
      await mergeChunks({ chunk_ids: targets.map((c) => c.chunk_id) });
      message.success('合并成功');
      setSelectedIds([]);
      await loadChunks();
    } finally {
      setMerging(false);
    }
  };

  const openSplit = (chunk: KbChunk) => {
    setSplitTarget(chunk);
    setSplitOffsets([]);
    setSplitManualInput('');
  };

  const addOffsetFromCaret = (el: HTMLTextAreaElement) => {
    if (!splitTarget) return;
    const pos = el.selectionStart;
    if (pos === null || pos <= 0 || pos >= splitTarget.content.length) return;
    setSplitOffsets((prev) => (prev.includes(pos) ? prev : [...prev, pos].sort((a, b) => a - b)));
  };

  const applyManualOffsets = () => {
    const parsed = splitManualInput
      .split(',')
      .map((s) => Number.parseInt(s.trim(), 10))
      .filter((n) => Number.isFinite(n));
    setSplitOffsets(Array.from(new Set(parsed)).sort((a, b) => a - b));
  };

  const removeOffset = (offset: number) => {
    setSplitOffsets((prev) => prev.filter((o) => o !== offset));
  };

  const handleConfirmSplit = async () => {
    if (!splitTarget) return;
    if (splitOffsets.length === 0) {
      message.warning('请至少选择一个拆分点');
      return;
    }
    const inBounds = splitOffsets.every((o) => o > 0 && o < splitTarget.content.length);
    if (!inBounds) {
      message.warning('拆分偏移必须落在正文范围内');
      return;
    }
    setSplitting(true);
    try {
      await splitChunk(splitTarget.chunk_id, { split_offsets: splitOffsets });
      message.success('拆分成功');
      setSplitTarget(null);
      await loadChunks();
    } finally {
      setSplitting(false);
    }
  };

  return (
    <Drawer
      title={`标注工作台${docName ? ` - ${docName}` : ''}`}
      open={docId !== null}
      onClose={onClose}
      width={720}
      destroyOnHidden
      extra={canWrite &&
        <Button size="small" type="primary" disabled={selectedIds.length < 2} loading={merging} onClick={handleMerge}>
          合并所选（{selectedIds.length}）
        </Button>
      }
    >
      <Spin spinning={loading}>
        {chunks.length === 0 && !loading ? (
          <Empty description="暂无分片，文档可能仍在处理中" />
        ) : (
          <List
            itemLayout="vertical"
            dataSource={chunks}
            renderItem={(chunk) => {
              // Server-computed over the whole document version (ChunkResponse.disabled_child_ids),
              // not derived from the rows on this page -- deriving it under-reported whenever a
              // parent and its children fell on different pages of this paginated list.
              const disabledChildIds = chunk.disabled_child_ids;
              const isEditing = editingId === chunk.chunk_id;
              const isSaving = savingId === chunk.chunk_id;
              return (
                <List.Item key={chunk.chunk_id} style={{ opacity: chunk.enabled ? 1 : 0.55 }}>
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
                      <Space wrap>
                        <Checkbox style={{ display: canWrite ? undefined : 'none' }} aria-label={`选择分片 ${chunk.seq}`}
                          checked={selectedIds.includes(chunk.chunk_id)}
                          onChange={(e) => toggleSelected(chunk.chunk_id, e.target.checked)}
                        />
                        <Tag>seq: {chunk.seq}</Tag>
                        <Tag color={metaOf(EMBEDDING_STATUS_META, chunk.embedding_status).color}>
                          {metaOf(EMBEDDING_STATUS_META, chunk.embedding_status).label}
                        </Tag>
                      </Space>
                      {canWrite && <Space>
                        <Switch aria-label={`启用分片 ${chunk.seq}`}
                          size="small"
                          checked={chunk.enabled}
                          checkedChildren="启用"
                          unCheckedChildren="禁用"
                          loading={isSaving}
                          onChange={(checked) => handleToggle(chunk, checked)}
                        />
                        {!isEditing && (
                          <Button size="small" onClick={() => startEdit(chunk)}>
                            编辑
                          </Button>
                        )}
                        <Button size="small" onClick={() => openSplit(chunk)}>
                          拆分
                        </Button>
                      </Space>}
                    </Space>

                    {disabledChildIds.length > 0 && (
                      <Alert type="warning" showIcon message={`子片已禁用：${disabledChildIds.join('、')}`} />
                    )}

                    {isEditing ? (
                      <>
                        <TextArea
                          value={editingContent}
                          onChange={(e) => setEditingContent(e.target.value)}
                          autoSize={{ minRows: 3, maxRows: 10 }}
                        />
                        <Space>
                          <Button size="small" type="primary" loading={isSaving} onClick={() => handleSaveEdit(chunk)}>
                            保存
                          </Button>
                          <Button size="small" onClick={cancelEdit}>
                            取消
                          </Button>
                        </Space>
                      </>
                    ) : (
                      <Typography.Paragraph
                        style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}
                        ellipsis={{ rows: 6, expandable: true, symbol: '展开全文' }}
                      >
                        {chunk.content}
                      </Typography.Paragraph>
                    )}

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
              );
            }}
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

      <Modal
        title={`拆分分片${splitTarget ? ` - seq ${splitTarget.seq}` : ''}`}
        open={splitTarget !== null}
        onCancel={() => setSplitTarget(null)}
        onOk={handleConfirmSplit}
        okText="确认拆分"
        cancelText="取消"
        confirmLoading={splitting}
        width={640}
        destroyOnHidden
      >
        {splitTarget && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Typography.Text type="secondary">点击下方正文中的位置插入拆分点，或在下面输入以逗号分隔的偏移列表</Typography.Text>
            <TextArea
              value={splitTarget.content}
              readOnly
              autoSize={{ minRows: 4, maxRows: 12 }}
              onClick={(e) => addOffsetFromCaret(e.currentTarget)}
            />
            <Space wrap>
              {splitOffsets.length === 0 ? (
                <Typography.Text type="secondary">尚未选择拆分点</Typography.Text>
              ) : (
                splitOffsets.map((offset) => (
                  <Tag key={offset} closable onClose={() => removeOffset(offset)}>
                    {offset}
                  </Tag>
                ))
              )}
            </Space>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                placeholder="手动输入偏移列表，如 10,30,60"
                value={splitManualInput}
                onChange={(e) => setSplitManualInput(e.target.value)}
              />
              <Button onClick={applyManualOffsets}>应用</Button>
            </Space.Compact>
          </Space>
        )}
      </Modal>
    </Drawer>
  );
}

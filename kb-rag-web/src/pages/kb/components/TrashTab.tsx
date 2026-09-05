import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Button, Popconfirm, Space, Table, Tooltip, Typography, message } from 'antd';
import { listTrash, purgeDocument, restoreDocument } from '../../../api/document';
import type { KbDocument } from '../../../api/types';
import { formatFileSize } from '../../../utils/format';

interface TrashTabProps {
  kbId: string;
  /** Fired after a successful restore so the parent can refresh the live document list. */
  onRestored: () => void;
}

const PAGE_SIZE = 20;

/**
 * 回收站 tab of the KB detail page (M11-CONTRACTS.md section 2.2): lists trashed documents most
 * recently trashed first, with restore (instant flag flip) and purge (irreversible, engine copies
 * included). Purge sits behind a Popconfirm because it is the second half of the two-step delete
 * the trash exists for -- the first DELETE already needed a confirmation on the documents tab.
 */
export default function TrashTab({ kbId, onRestored }: TrashTabProps) {
  const { can } = useAuth();
  const canReview = can(PERMISSIONS.DOC_REVIEW);
  const [items, setItems] = useState<KbDocument[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  // doc_id of the row whose restore/purge request is in flight, to scope the button spinner.
  const [actingId, setActingId] = useState<string | null>(null);

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    try {
      const result = await listTrash(kbId, targetPage);
      setItems(result.items);
      setTotal(result.total);
      setPage(targetPage);
    } finally {
      setLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    load(1);
  }, [load]);

  const handleRestore = async (row: KbDocument) => {
    setActingId(row.doc_id);
    try {
      await restoreDocument(row.doc_id);
      message.success(`已还原 ${row.file_name}`);
      load(page);
      onRestored();
    } finally {
      setActingId(null);
    }
  };

  const handlePurge = async (row: KbDocument) => {
    setActingId(row.doc_id);
    try {
      await purgeDocument(row.doc_id);
      message.success(`已彻底删除 ${row.file_name}`);
      load(page);
    } finally {
      setActingId(null);
    }
  };

  return (
    <>
      <Typography.Paragraph type="secondary">
        回收站中的文档不参与检索，可随时还原；超过保留期后会被定时任务自动清除。
      </Typography.Paragraph>

      <Table<KbDocument>
        rowKey="doc_id"
        loading={loading}
        dataSource={items}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (nextPage) => load(nextPage),
        }}
        columns={[
          {
            title: '文件名',
            dataIndex: 'file_name',
            ellipsis: { showTitle: false },
            render: (name: string) => (
              <Tooltip title={name} placement="topLeft">
                {name}
              </Tooltip>
            ),
          },
          { title: '类型', dataIndex: 'file_ext', width: 80 },
          {
            title: '大小',
            dataIndex: 'file_size',
            width: 100,
            render: (size: number) => formatFileSize(size),
          },
          { title: '移入时间', dataIndex: 'trashed_at', width: 180 },
          {
            title: '操作',
            width: 200,
            render: (_, record) => canReview ? (
              <Space>
                <Button
                  size="small"
                  type="link"
                  loading={actingId === record.doc_id}
                  onClick={() => handleRestore(record)}
                >
                  还原
                </Button>
                <Popconfirm
                  title="彻底删除该文档？"
                  description={
                    <>
                      将连同它的<b>全部版本与分片</b>一并删除（含两个检索引擎中的副本），
                      <br />
                      删除后不可恢复，如需重新入库须重新上传。
                    </>
                  }
                  okText="彻底删除"
                  okButtonProps={{ danger: true }}
                  cancelText="取消"
                  onConfirm={() => handlePurge(record)}
                >
                  <Button size="small" type="link" danger loading={actingId === record.doc_id}>
                    彻底删除
                  </Button>
                </Popconfirm>
              </Space>
            ) : null,
          },
        ]}
      />
    </>
  );
}

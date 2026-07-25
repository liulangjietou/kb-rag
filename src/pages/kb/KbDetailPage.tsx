import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeftOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Button,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  Upload,
  message,
} from 'antd';
import type { UploadProps } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { listDocuments, reindexDocument, uploadDocument } from '../../api/document';
import { getKnowledgeBase } from '../../api/kb';
import type { KbDocument, KnowledgeBase } from '../../api/types';
import { formatFileSize } from '../../utils/format';
import { PROCESS_STATUS_META } from '../../utils/statusMeta';
import ChunkDrawer from './components/ChunkDrawer';

// Document list is polled every 3s while this page stays mounted, per M1-CONTRACTS.md section 7.
const POLL_INTERVAL_MS = 3000;

export default function KbDetailPage() {
  const { kbId } = useParams<{ kbId: string }>();
  const navigate = useNavigate();
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [documents, setDocuments] = useState<KbDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [chunkDoc, setChunkDoc] = useState<KbDocument | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadDocuments = useCallback(async () => {
    if (!kbId) return;
    const result = await listDocuments(kbId);
    setDocuments(result.items);
  }, [kbId]);

  useEffect(() => {
    if (!kbId) return;
    setLoading(true);
    Promise.all([getKnowledgeBase(kbId), loadDocuments()])
      .then(([kbDetail]) => setKb(kbDetail))
      .finally(() => setLoading(false));
  }, [kbId, loadDocuments]);

  useEffect(() => {
    pollTimerRef.current = setInterval(() => {
      loadDocuments();
    }, POLL_INTERVAL_MS);
    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
      }
    };
  }, [loadDocuments]);

  const handleReindex = async (docId: string) => {
    await reindexDocument(docId);
    message.success('已提交重建任务');
    loadDocuments();
  };

  const uploadProps: UploadProps = {
    multiple: true,
    showUploadList: false,
    customRequest: async (options) => {
      const { file, onSuccess, onError } = options;
      try {
        const doc = await uploadDocument(kbId!, file as File);
        onSuccess?.(doc);
        message.success(`${(file as File).name} 上传成功，正在处理`);
        loadDocuments();
      } catch (err) {
        onError?.(err as Error);
      }
    },
  };

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/kb')}>
          返回列表
        </Button>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {kb?.name ?? '知识库详情'}
        </Typography.Title>
      </Space>
      {kb?.description && (
        <Typography.Paragraph type="secondary">{kb.description}</Typography.Paragraph>
      )}

      <Upload.Dragger {...uploadProps} style={{ marginBottom: 24 }}>
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">点击或拖拽文件到此处上传</p>
        <p className="ant-upload-hint">
          支持 pdf / docx / txt / md / xlsx / csv，单文件不超过 100MB，可批量上传
        </p>
      </Upload.Dragger>

      <Table<KbDocument>
        rowKey="doc_id"
        loading={loading}
        dataSource={documents}
        pagination={false}
        columns={[
          { title: '文件名', dataIndex: 'file_name' },
          { title: '类型', dataIndex: 'file_ext', width: 80 },
          {
            title: '大小',
            dataIndex: 'file_size',
            width: 100,
            render: (size: number) => formatFileSize(size),
          },
          {
            title: '处理状态',
            dataIndex: 'process_status',
            width: 160,
            render: (status: KbDocument['process_status'], record: KbDocument) => {
              const meta = PROCESS_STATUS_META[status];
              const tag = <Tag color={meta.color}>{meta.label}</Tag>;
              return record.fail_reason ? (
                <Tooltip title={record.fail_reason}>{tag}</Tooltip>
              ) : (
                tag
              );
            },
          },
          {
            title: '配置',
            dataIndex: 'config_stale',
            width: 90,
            render: (stale: boolean) => (stale ? <Tag color="warning">配置过期</Tag> : null),
          },
          {
            title: '操作',
            width: 200,
            render: (_, record: KbDocument) => (
              <Space>
                <Button size="small" onClick={() => setChunkDoc(record)}>
                  查看分片
                </Button>
                <Popconfirm
                  title="确认重建该文档的解析与索引？"
                  okText="重建"
                  cancelText="取消"
                  onConfirm={() => handleReindex(record.doc_id)}
                >
                  <Button size="small" icon={<ReloadOutlined />}>
                    重建
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <ChunkDrawer
        docId={chunkDoc?.doc_id ?? null}
        docName={chunkDoc?.file_name ?? null}
        onClose={() => setChunkDoc(null)}
      />
    </div>
  );
}

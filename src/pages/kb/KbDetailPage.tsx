import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  InboxOutlined,
  MessageOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Popconfirm,
  Progress,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  Upload,
  message,
} from 'antd';
import type { UploadProps } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { listDocuments, reindexDocument, uploadDocument } from '../../api/document';
import { confirmKbDocuments, getKnowledgeBase, rebuildKb } from '../../api/kb';
import type { KbDocument, KnowledgeBase } from '../../api/types';
import { formatFileSize } from '../../utils/format';
import { PROCESS_STATUS_META, metaOf } from '../../utils/statusMeta';
import ChatImportWizard from './components/ChatImportWizard';
import ChunkDrawer from './components/ChunkDrawer';
import GraphTab from './components/GraphTab';
import IndexConfigDrawer from './components/IndexConfigDrawer';
import ParsePreviewDrawer from './components/ParsePreviewDrawer';
import VersionDrawer from './components/VersionDrawer';

// Document list is polled every 3s while this page stays mounted, per M1-CONTRACTS.md section 7.
// The same poll loop is reused to track rebuild progress (M2-CONTRACTS.md section 4): there is
// no dedicated task-status endpoint in the contract, so completion is derived from watching
// each targeted document's config_stale flag flip back to false.
const POLL_INTERVAL_MS = 3000;

export default function KbDetailPage() {
  const { kbId } = useParams<{ kbId: string }>();
  const navigate = useNavigate();
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [documents, setDocuments] = useState<KbDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [chunkDoc, setChunkDoc] = useState<KbDocument | null>(null);
  const [previewDoc, setPreviewDoc] = useState<KbDocument | null>(null);
  const [versionDocId, setVersionDocId] = useState<string | null>(null);
  const [chatImportOpen, setChatImportOpen] = useState(false);
  const [indexConfigOpen, setIndexConfigOpen] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);
  const [rebuildTargetIds, setRebuildTargetIds] = useState<string[]>([]);
  const [rebuildInitialCount, setRebuildInitialCount] = useState(0);
  const [selectedPendingIds, setSelectedPendingIds] = useState<string[]>([]);
  const [batchConfirming, setBatchConfirming] = useState(false);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadKb = useCallback(async () => {
    if (!kbId) return;
    const detail = await getKnowledgeBase(kbId);
    setKb(detail);
  }, [kbId]);

  const loadDocuments = useCallback(async () => {
    if (!kbId) return;
    const result = await listDocuments(kbId);
    setDocuments(result.items);
  }, [kbId]);

  useEffect(() => {
    if (!kbId) return;
    setLoading(true);
    Promise.all([loadKb(), loadDocuments()]).finally(() => setLoading(false));
  }, [kbId, loadKb, loadDocuments]);

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

  const staleDocs = useMemo(() => documents.filter((doc) => doc.config_stale), [documents]);
  const remainingRebuildCount = useMemo(
    () => documents.filter((doc) => rebuildTargetIds.includes(doc.doc_id) && doc.config_stale).length,
    [documents, rebuildTargetIds],
  );
  // M3-CONTRACTS.md section 3.4/4: documents paused on parse-preview confirmation.
  const pendingConfirmDocs = useMemo(
    () => documents.filter((doc) => doc.process_status === 'PENDING_CONFIRM'),
    [documents],
  );
  // Looked up from the live, poll-refreshed `documents` array (not a static snapshot) so
  // VersionDrawer's REBUILD-progress hint reflects the same 3s poll this page already runs.
  const versionDoc = useMemo(
    () => documents.find((doc) => doc.doc_id === versionDocId) ?? null,
    [documents, versionDocId],
  );

  // Once every targeted document's config_stale flag clears, consider the rebuild finished.
  useEffect(() => {
    if (!rebuilding || rebuildTargetIds.length === 0) return;
    if (remainingRebuildCount === 0) {
      setRebuilding(false);
      setRebuildTargetIds([]);
      message.success('重建完成，新分片配置已生效');
    }
  }, [rebuilding, rebuildTargetIds, remainingRebuildCount]);

  // Selection can only ever reference documents still pending confirmation; drop stale ids once
  // a document leaves that state (e.g. confirmed from the preview drawer directly).
  useEffect(() => {
    setSelectedPendingIds((prev) => prev.filter((id) => pendingConfirmDocs.some((doc) => doc.doc_id === id)));
  }, [pendingConfirmDocs]);

  const handleReindex = async (docId: string) => {
    await reindexDocument(docId);
    message.success('已提交重建任务');
    loadDocuments();
  };

  const handleRebuildStale = async () => {
    if (!kbId || staleDocs.length === 0) return;
    const targetIds = staleDocs.map((doc) => doc.doc_id);
    await rebuildKb(kbId, { doc_ids: targetIds });
    message.success('已提交按新配置重建任务');
    setRebuildTargetIds(targetIds);
    setRebuildInitialCount(targetIds.length);
    setRebuilding(true);
    loadDocuments();
  };

  const handleIndexConfigSaved = () => {
    loadKb();
    loadDocuments();
  };

  const handlePreviewConfirmed = () => {
    setPreviewDoc(null);
    loadDocuments();
  };

  const handleChatImported = () => {
    setChatImportOpen(false);
    loadDocuments();
  };

  const handleVersionActivated = () => {
    loadDocuments();
  };

  const handleBatchConfirm = async () => {
    if (!kbId || pendingConfirmDocs.length === 0) return;
    setBatchConfirming(true);
    try {
      const docIds = selectedPendingIds.length > 0 ? selectedPendingIds : undefined;
      await confirmKbDocuments(kbId, docIds ? { doc_ids: docIds } : undefined);
      message.success('已确认入库');
      setSelectedPendingIds([]);
      loadDocuments();
    } finally {
      setBatchConfirming(false);
    }
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
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/kb')}>
            返回列表
          </Button>
          <Typography.Title level={4} style={{ margin: 0 }}>
            {kb?.name ?? '知识库详情'}
          </Typography.Title>
        </Space>
        <Space>
          <Button icon={<MessageOutlined />} onClick={() => setChatImportOpen(true)}>
            导入聊天记录
          </Button>
          <Button icon={<SettingOutlined />} onClick={() => setIndexConfigOpen(true)}>
            索引配置
          </Button>
        </Space>
      </Space>
      {kb?.description && (
        <Typography.Paragraph type="secondary">{kb.description}</Typography.Paragraph>
      )}

      <Tabs
        items={[
          {
            key: 'documents',
            label: '文档管理',
            children: (
              <>
                {staleDocs.length > 0 && (
                  <Alert
                    type="warning"
                    showIcon
                    message={`${staleDocs.length} 篇文档使用旧配置`}
                    description={
                      rebuilding ? (
                        <Progress
                          percent={Math.round(((rebuildInitialCount - remainingRebuildCount) / rebuildInitialCount) * 100)}
                          size="small"
                          status="active"
                        />
                      ) : (
                        '索引配置已变更，需要按新配置重建后才能生效'
                      )
                    }
                    action={
                      <Button size="small" type="primary" loading={rebuilding} disabled={rebuilding} onClick={handleRebuildStale}>
                        {rebuilding ? '重建中' : '按新配置重建'}
                      </Button>
                    }
                    style={{ marginBottom: 16 }}
                  />
                )}

                {pendingConfirmDocs.length > 0 && (
                  <Alert
                    type="info"
                    showIcon
                    message={`${pendingConfirmDocs.length} 篇文档待预览确认`}
                    description="已开启解析预览确认，文档清洗完成后会暂停在此状态；可逐篇预览后确认，或直接批量确认全部"
                    action={
                      <Button
                        size="small"
                        type="primary"
                        icon={<CheckOutlined />}
                        loading={batchConfirming}
                        onClick={handleBatchConfirm}
                      >
                        批量确认{selectedPendingIds.length > 0 ? `（${selectedPendingIds.length}）` : '（全部）'}
                      </Button>
                    }
                    style={{ marginBottom: 16 }}
                  />
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
                  rowSelection={{
                    selectedRowKeys: selectedPendingIds,
                    onChange: (keys) => setSelectedPendingIds(keys as string[]),
                    getCheckboxProps: (record) => ({ disabled: record.process_status !== 'PENDING_CONFIRM' }),
                  }}
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
                        const meta = metaOf(PROCESS_STATUS_META, status);
                        const tag = <Tag color={meta.color}>{meta.label}</Tag>;
                        return record.fail_reason ? (
                          <Tooltip title={record.fail_reason}>{tag}</Tooltip>
                        ) : (
                          tag
                        );
                      },
                    },
                    {
                      title: '索引配置',
                      dataIndex: 'config_stale',
                      width: 100,
                      render: (stale: boolean) => (stale ? <Tag color="warning">配置过期</Tag> : <Tag color="success">最新</Tag>),
                    },
                    {
                      title: '操作',
                      width: 260,
                      render: (_, record: KbDocument) => (
                        <Space>
                          <Button size="small" onClick={() => setChunkDoc(record)}>
                            查看分片
                          </Button>
                          <Button size="small" onClick={() => setVersionDocId(record.doc_id)}>
                            版本
                          </Button>
                          {record.process_status === 'PENDING_CONFIRM' && (
                            <Button size="small" type="link" onClick={() => setPreviewDoc(record)}>
                              预览确认
                            </Button>
                          )}
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
              </>
            ),
          },
          {
            key: 'graph',
            label: '知识图谱',
            children: kbId ? <GraphTab kbId={kbId} kb={kb} onKbChanged={loadKb} /> : null,
          },
        ]}
      />

      <ChunkDrawer
        docId={chunkDoc?.doc_id ?? null}
        docName={chunkDoc?.file_name ?? null}
        onClose={() => setChunkDoc(null)}
      />

      <ParsePreviewDrawer
        doc={previewDoc}
        onClose={() => setPreviewDoc(null)}
        onConfirmed={handlePreviewConfirmed}
      />

      <VersionDrawer doc={versionDoc} onClose={() => setVersionDocId(null)} onActivated={handleVersionActivated} />

      {kbId && (
        <ChatImportWizard
          kbId={kbId}
          open={chatImportOpen}
          onClose={() => setChatImportOpen(false)}
          onImported={handleChatImported}
        />
      )}

      {kbId && (
        <IndexConfigDrawer
          kbId={kbId}
          open={indexConfigOpen}
          indexConfig={kb?.index_config ?? null}
          onClose={() => setIndexConfigOpen(false)}
          onSaved={handleIndexConfigSaved}
        />
      )}
    </div>
  );
}

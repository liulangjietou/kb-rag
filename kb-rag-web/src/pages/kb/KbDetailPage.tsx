// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  DeleteOutlined,
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
  Switch,
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
import {
  approveDocument,
  deleteDocument,
  listDocuments,
  reindexDocument,
  submitDocumentReview,
  uploadDocument,
} from '../../api/document';
import { confirmKbDocuments, getKnowledgeBase, rebuildKb, updateKbGovernance } from '../../api/kb';
import { PUBLISH_STATUS_META } from '../../api/types';
import type { KbDocument, KnowledgeBase } from '../../api/types';
import { formatFileSize } from '../../utils/format';
import { PROCESS_STATUS_META, metaOf } from '../../utils/statusMeta';
import ChatImportWizard from './components/ChatImportWizard';
import ChunkDrawer from './components/ChunkDrawer';
import FeedbackTab from './components/FeedbackTab';
import { RejectModal, ValidityModal } from './components/GovernanceModals';
import GraphTab from './components/GraphTab';
import IndexConfigDrawer from './components/IndexConfigDrawer';
import InsightTab from './components/InsightTab';
import ParsePreviewDrawer from './components/ParsePreviewDrawer';
import TrashTab from './components/TrashTab';
import VersionDrawer from './components/VersionDrawer';
import WebSourcesTab from './components/WebSourcesTab';

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
  const [deletingId, setDeletingId] = useState<string | null>(null);
  // M11 governance: rows the validity/reject modals are pointing at, and the KB-level switch.
  const [validityDoc, setValidityDoc] = useState<KbDocument | null>(null);
  const [rejectDoc, setRejectDoc] = useState<KbDocument | null>(null);
  const [governanceSaving, setGovernanceSaving] = useState(false);
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

  const handleDelete = async (doc: KbDocument) => {
    setDeletingId(doc.doc_id);
    try {
      await deleteDocument(doc.doc_id);
      message.success(`已将 ${doc.file_name} 移入回收站`);
      // Drop any drawer still pointing at the document that no longer exists.
      setChunkDoc((prev) => (prev?.doc_id === doc.doc_id ? null : prev));
      setPreviewDoc((prev) => (prev?.doc_id === doc.doc_id ? null : prev));
      setVersionDocId((prev) => (prev === doc.doc_id ? null : prev));
      loadDocuments();
    } finally {
      setDeletingId(null);
    }
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

  const handleSubmitReview = async (doc: KbDocument) => {
    await submitDocumentReview(doc.doc_id);
    message.success('已提交审核');
    loadDocuments();
  };

  const handleApprove = async (doc: KbDocument) => {
    await approveDocument(doc.doc_id);
    message.success(`已通过并发布 ${doc.file_name}`);
    loadDocuments();
  };

  // KB-level review_required switch (M11-CONTRACTS.md section 2.2): only affects future uploads,
  // which is why flipping it does not touch loadDocuments.
  const handleGovernanceToggle = async (checked: boolean) => {
    if (!kbId) return;
    setGovernanceSaving(true);
    try {
      await updateKbGovernance(kbId, checked);
      message.success(checked ? '已开启审核：之后上传的新文档需审核通过后才参与检索' : '已关闭审核：之后上传的新文档直接发布');
      loadKb();
    } finally {
      setGovernanceSaving(false);
    }
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
        // The upload response carries what M4a's three-branch dedup actually decided
        // (duplicated / new version / brand new document); reporting a flat "上传成功" hid the
        // case where nothing was re-parsed because the content hash already existed.
        const name = (file as File).name;
        if (doc.duplicated) {
          message.info(`${name} 内容与已有版本${doc.version ? ` ${doc.version}` : ''}一致，未重复建版`);
        } else if (doc.version) {
          message.success(`${name} 上传成功，已生成版本 ${doc.version}，正在处理`);
        } else {
          message.success(`${name} 上传成功，正在处理`);
        }
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
          <Tooltip title="开启后，之后上传的新文档默认为草稿，需提交审核并通过后才参与检索；已有文档不受影响">
            <Space size={4}>
              <Typography.Text type="secondary">新文档需审核</Typography.Text>
              <Switch
                size="small"
                checked={kb?.review_required ?? false}
                loading={governanceSaving}
                onChange={handleGovernanceToggle}
              />
            </Space>
          </Tooltip>
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
                    支持 pdf / docx / txt / md / xlsx / csv / html，单文件不超过 100MB，可批量上传
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
                      width: 120,
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
                      title: '发布状态',
                      dataIndex: 'publish_status',
                      width: 100,
                      render: (status: KbDocument['publish_status'], record: KbDocument) => {
                        const meta = metaOf(PUBLISH_STATUS_META, status);
                        const tag = <Tag color={meta.color}>{meta.label}</Tag>;
                        // Surface the rejection reason right on the tag, per M11's review loop.
                        return status === 'REJECTED' && record.review_note ? (
                          <Tooltip title={`驳回原因：${record.review_note}`}>{tag}</Tooltip>
                        ) : (
                          tag
                        );
                      },
                    },
                    {
                      title: '有效期',
                      width: 90,
                      render: (_, record: KbDocument) =>
                        record.effective_at || record.expires_at ? (
                          <Tooltip
                            title={`生效：${record.effective_at ?? '不限'}，失效：${record.expires_at ?? '不限'}`}
                          >
                            <Tag
                              color={
                                record.expires_at && new Date(record.expires_at) <= new Date() ? 'error' : 'blue'
                              }
                            >
                              {record.expires_at && new Date(record.expires_at) <= new Date() ? '已过期' : '已设置'}
                            </Tag>
                          </Tooltip>
                        ) : (
                          <Tag color="default">长期</Tag>
                        ),
                    },
                    {
                      title: '索引配置',
                      dataIndex: 'config_stale',
                      width: 100,
                      render: (stale: boolean) => (stale ? <Tag color="warning">配置过期</Tag> : <Tag color="success">最新</Tag>),
                    },
                    {
                      title: '操作',
                      width: 380,
                      render: (_, record: KbDocument) => (
                        <Space wrap>
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
                          {(record.publish_status === 'DRAFT' || record.publish_status === 'REJECTED') && (
                            <Button size="small" type="link" onClick={() => handleSubmitReview(record)}>
                              提交审核
                            </Button>
                          )}
                          {record.publish_status === 'PENDING_REVIEW' && (
                            <>
                              <Popconfirm
                                title="通过审核并发布？"
                                description="发布后文档即参与检索，且不可退回草稿（下架需设置失效时间或移入回收站）"
                                okText="通过"
                                cancelText="取消"
                                onConfirm={() => handleApprove(record)}
                              >
                                <Button size="small" type="link">
                                  通过
                                </Button>
                              </Popconfirm>
                              <Button size="small" type="link" danger onClick={() => setRejectDoc(record)}>
                                驳回
                              </Button>
                            </>
                          )}
                          <Button size="small" type="link" onClick={() => setValidityDoc(record)}>
                            有效期
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
                          <Popconfirm
                            title="移入回收站？"
                            description={
                              <>
                                文档将移入回收站并立即从检索中下线；
                                <br />
                                可在「回收站」标签页随时还原，超过保留期后自动清除。
                              </>
                            }
                            okText="移入回收站"
                            okButtonProps={{ danger: true }}
                            cancelText="取消"
                            onConfirm={() => handleDelete(record)}
                          >
                            <Button size="small" danger icon={<DeleteOutlined />} loading={deletingId === record.doc_id}>
                              删除
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
          // M10-CONTRACTS.md section 3: the retrieval quality loop's two console entrances.
          {
            key: 'feedback',
            label: '反馈管理',
            children: kbId ? <FeedbackTab kbId={kbId} /> : null,
          },
          {
            key: 'insight',
            label: '检索洞察',
            children: kbId ? <InsightTab kbId={kbId} /> : null,
          },
          // M11-CONTRACTS.md section 2.2: reversible deletion lives here until retention expiry.
          {
            key: 'trash',
            label: '回收站',
            children: kbId ? <TrashTab kbId={kbId} onRestored={loadDocuments} /> : null,
          },
          // M12-CONTRACTS.md section 3.4: URL registration + incremental sync management.
          {
            key: 'webSources',
            label: '网页导入',
            children: kbId ? <WebSourcesTab kbId={kbId} onSynced={loadDocuments} /> : null,
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

      <ValidityModal doc={validityDoc} onClose={() => setValidityDoc(null)} onSaved={loadDocuments} />

      <RejectModal doc={rejectDoc} onClose={() => setRejectDoc(null)} onRejected={loadDocuments} />

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

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
import {
  batchDeleteDocuments,
  batchReindexDocuments,
  confirmKbDocuments,
  getKnowledgeBase,
  rebuildKb,
  updateKbGovernance,
} from '../../api/kb';
import { PUBLISH_STATUS_META } from '../../api/types';
import type { KbDocument, KnowledgeBase } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import { formatFileSize } from '../../utils/format';
import { PROCESS_STATUS_META, metaOf } from '../../utils/statusMeta';
import ChatImportWizard from './components/ChatImportWizard';
import ChunkDrawer from './components/ChunkDrawer';
import ExternalSourceTab from './components/ExternalSourceTab';
import FeedbackTab from './components/FeedbackTab';
import { RejectModal, ValidityModal } from './components/GovernanceModals';
import GraphTab from './components/GraphTab';
import IndexConfigDrawer from './components/IndexConfigDrawer';
import InsightTab from './components/InsightTab';
import ParsePreviewDrawer from './components/ParsePreviewDrawer';
import TrashTab from './components/TrashTab';
import VersionDrawer from './components/VersionDrawer';
import VisibilityDrawer from './components/VisibilityDrawer';
import WebSourcesTab from './components/WebSourcesTab';

// Document list is polled every 3s while this page stays mounted, per M1-CONTRACTS.md section 7.
// The same poll loop is reused to track rebuild progress (M2-CONTRACTS.md section 4): there is
// no dedicated task-status endpoint in the contract, so completion is derived from watching
// each targeted document's config_stale flag flip back to false.
const POLL_INTERVAL_MS = 3000;

// 与 DocumentController 的 DEFAULT_PAGE_SIZE 对齐：首屏不传 size 时服务端就按 20 返回，
// 前端跟着写 20 才不会出现"表格标了每页 10 条、实际拿回 20 条"的错位。
const DEFAULT_DOC_PAGE_SIZE = 20;
const DOC_PAGE_SIZE_OPTIONS = ['20', '50', '100'];

export default function KbDetailPage() {
  const { kbId } = useParams<{ kbId: string }>();
  const navigate = useNavigate();
  const { can } = useAuth();
  // M16: the visibility editor writes through a doc:review endpoint, so only reviewers get it.
  const canDocReview = can(PERMISSIONS.DOC_REVIEW);
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [documents, setDocuments] = useState<KbDocument[]>([]);
  const [docPage, setDocPage] = useState(1);
  const [docPageSize, setDocPageSize] = useState(DEFAULT_DOC_PAGE_SIZE);
  const [docTotal, setDocTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [chunkDoc, setChunkDoc] = useState<KbDocument | null>(null);
  const [previewDoc, setPreviewDoc] = useState<KbDocument | null>(null);
  const [versionDocId, setVersionDocId] = useState<string | null>(null);
  const [chatImportOpen, setChatImportOpen] = useState(false);
  const [indexConfigOpen, setIndexConfigOpen] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);
  const [rebuildTargetIds, setRebuildTargetIds] = useState<string[]>([]);
  const [rebuildInitialCount, setRebuildInitialCount] = useState(0);
  // 表格只有一套勾选：批量删除、批量重建作用于全部勾选项，批量确认取其中还在等确认的那部分。
  const [selectedDocIds, setSelectedDocIds] = useState<string[]>([]);
  const [batchConfirming, setBatchConfirming] = useState(false);
  const [batchDeleting, setBatchDeleting] = useState(false);
  const [batchReindexing, setBatchReindexing] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  // M11 governance: rows the validity/reject modals are pointing at, and the KB-level switch.
  const [validityDoc, setValidityDoc] = useState<KbDocument | null>(null);
  const [rejectDoc, setRejectDoc] = useState<KbDocument | null>(null);
  // M16 document visibility: the row the drawer is editing.
  const [visibilityDoc, setVisibilityDoc] = useState<KbDocument | null>(null);
  const [governanceSaving, setGovernanceSaving] = useState(false);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadKb = useCallback(async () => {
    if (!kbId) return;
    const detail = await getKnowledgeBase(kbId);
    setKb(detail);
  }, [kbId]);

  /**
   * 翻页状态放在 ref 里而不是进 useCallback 依赖：这个函数有二十余处无参调用（上传、删除、
   * 重建、各 Tab 的回调），若依赖 page 就会在每次翻页时重建，连带把挂载时那个 useEffect
   * 再跑一遍。无参调用的语义因此是"刷新当前页"，翻页则显式传页码。
   */
  const docPageRef = useRef(1);
  const docPageSizeRef = useRef(DEFAULT_DOC_PAGE_SIZE);

  const loadDocuments = useCallback(async (page?: number, size?: number) => {
    if (!kbId) return;
    const targetPage = page ?? docPageRef.current;
    const targetSize = size ?? docPageSizeRef.current;
    const result = await listDocuments(kbId, { page: targetPage, size: targetSize });
    // 删掉末页最后一条后该页会空掉，此时按 total 直接跳到真正的末页——逐页回退在页码
    // 远超范围时会递归几十次，而服务端对越界页码只是返回空列表、并不纠正 page
    const lastPage = Math.max(1, Math.ceil(result.total / targetSize));
    if (result.items.length === 0 && targetPage > lastPage) {
      await loadDocuments(lastPage, targetSize);
      return;
    }
    setDocuments(result.items);
    setDocTotal(result.total);
    docPageRef.current = result.page;
    docPageSizeRef.current = result.size;
    setDocPage(result.page);
    setDocPageSize(result.size);
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

  // 勾选项里"还在等预览确认"的那部分：批量确认只该作用于它们，而删除与重建作用于全部勾选项。
  const selectedPendingConfirmIds = useMemo(
    () => selectedDocIds.filter((id) => pendingConfirmDocs.some((doc) => doc.doc_id === id)),
    [selectedDocIds, pendingConfirmDocs],
  );

  // 3 秒轮询会整页换掉 documents（别人删了、处理完了），勾选里指向已经不在列表上的文档必须跟着
  // 掉——这套勾选驱动的是删除，带上一个用户已经看不见的目标是危险的。引用不变时返回原数组，
  // 否则每轮轮询都会生成新数组，把整张表白白重渲染一遍。
  useEffect(() => {
    setSelectedDocIds((prev) => {
      const next = prev.filter((id) => documents.some((doc) => doc.doc_id === id));
      return next.length === prev.length ? prev : next;
    });
  }, [documents]);

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
      const docIds = selectedPendingConfirmIds.length > 0 ? selectedPendingConfirmIds : undefined;
      await confirmKbDocuments(kbId, docIds ? { doc_ids: docIds } : undefined);
      message.success('已确认入库');
      setSelectedDocIds([]);
      loadDocuments();
    } finally {
      setBatchConfirming(false);
    }
  };

  /**
   * 批量移入回收站。服务端会跳过勾选后又被别人删掉的文档，所以提示按"实际删掉了几篇"来写，
   * 而不是照搬勾选数——否则用户会以为删了 5 篇，实际只动了 3 篇。
   */
  const handleBatchDelete = async () => {
    if (!kbId || selectedDocIds.length === 0) return;
    setBatchDeleting(true);
    try {
      const { deleted_doc_ids: deletedIds } = await batchDeleteDocuments(kbId, selectedDocIds);
      const skipped = selectedDocIds.length - deletedIds.length;
      message.success(
        skipped > 0
          ? `已将 ${deletedIds.length} 个文档移入回收站，${skipped} 个已在回收站中，已跳过`
          : `已将 ${deletedIds.length} 个文档移入回收站`,
      );
      // 关掉还指向已删文档的抽屉，与单篇删除保持同样的收尾
      const deleted = new Set(deletedIds);
      setChunkDoc((prev) => (prev && deleted.has(prev.doc_id) ? null : prev));
      setPreviewDoc((prev) => (prev && deleted.has(prev.doc_id) ? null : prev));
      setVersionDocId((prev) => (prev && deleted.has(prev) ? null : prev));
      setSelectedDocIds([]);
      loadDocuments();
    } finally {
      setBatchDeleting(false);
    }
  };

  /** 批量重建：与每行的「重建」按钮同一语义（完整重跑解析与索引），只是一次提交一批。 */
  const handleBatchReindex = async () => {
    if (!kbId || selectedDocIds.length === 0) return;
    setBatchReindexing(true);
    try {
      const { reindexed_doc_ids: submittedIds } = await batchReindexDocuments(kbId, selectedDocIds);
      const skipped = selectedDocIds.length - submittedIds.length;
      message.success(
        skipped > 0
          ? `已提交 ${submittedIds.length} 个重建任务，${skipped} 个还没有可重建的版本，已跳过`
          : `已提交 ${submittedIds.length} 个重建任务`,
      );
      setSelectedDocIds([]);
      loadDocuments();
    } finally {
      setBatchReindexing(false);
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
                        批量确认
                        {selectedPendingConfirmIds.length > 0 ? `（${selectedPendingConfirmIds.length}）` : '（全部）'}
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
                    支持 pdf / docx / txt / md / sql / xlsx / csv / html，单文件不超过 100MB，可批量上传
                  </p>
                </Upload.Dragger>

                {selectedDocIds.length > 0 && (
                  <Alert
                    type="info"
                    showIcon
                    message={`已选中 ${selectedDocIds.length} 个文档`}
                    description="批量操作只作用于当前页勾选的文档；表头复选框可全选本页，其下拉菜单提供反选与清空"
                    action={
                      <Space>
                        <Popconfirm
                          title={`确认重建选中的 ${selectedDocIds.length} 个文档？`}
                          description="将重新解析、切分并写入索引，耗时随文档大小增长"
                          okText="重建"
                          cancelText="取消"
                          onConfirm={handleBatchReindex}
                        >
                          <Button size="small" icon={<ReloadOutlined />} loading={batchReindexing}>
                            批量重建
                          </Button>
                        </Popconfirm>
                        <Popconfirm
                          title={`将选中的 ${selectedDocIds.length} 个文档移入回收站？`}
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
                          onConfirm={handleBatchDelete}
                        >
                          <Button size="small" danger icon={<DeleteOutlined />} loading={batchDeleting}>
                            批量删除
                          </Button>
                        </Popconfirm>
                        <Button size="small" type="link" onClick={() => setSelectedDocIds([])}>
                          取消选择
                        </Button>
                      </Space>
                    }
                    style={{ marginBottom: 16 }}
                  />
                )}

                <Table<KbDocument>
                  rowKey="doc_id"
                  loading={loading}
                  dataSource={documents}
                  pagination={{
                    current: docPage,
                    pageSize: docPageSize,
                    total: docTotal,
                    showSizeChanger: true,
                    pageSizeOptions: DOC_PAGE_SIZE_OPTIONS,
                    showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 个文档`,
                    // 翻页即清空勾选：勾选驱动的是删除，"已选 30 个"里混进翻走后看不见的文档太危险
                    onChange: (page, size) => {
                      setSelectedDocIds([]);
                      loadDocuments(page, size);
                    },
                  }}
                  rowSelection={{
                    selectedRowKeys: selectedDocIds,
                    onChange: (keys) => setSelectedDocIds(keys as string[]),
                    // 全选 / 反选 / 清空，作用范围与表头复选框一致：当前页
                    selections: [Table.SELECTION_ALL, Table.SELECTION_INVERT, Table.SELECTION_NONE],
                  }}
                  columns={[
                    {
                      title: '文件名',
                      dataIndex: 'file_name',
                      render: (name: string, record: KbDocument) => (
                        <Space size={4}>
                          <Typography.Text>{name}</Typography.Text>
                          {record.restricted && (
                            // M16: restriction hides content, not the row -- the tag says why a
                            // reader may still be told "无权查看" when opening it.
                            <Tooltip title="仅限授权角色查看内容与命中检索">
                              <Tag color="orange">受限</Tag>
                            </Tooltip>
                          )}
                        </Space>
                      ),
                    },
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
                          {canDocReview && (
                            <Button size="small" type="link" onClick={() => setVisibilityDoc(record)}>
                              可见性
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
          // M14 contract section 2.3: S3/OSS compatible object store connector + per-object sync.
          {
            key: 'extSources',
            label: '外部数据源',
            children: kbId ? <ExternalSourceTab kbId={kbId} onSynced={loadDocuments} /> : null,
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
        <VisibilityDrawer
          kbId={kbId}
          doc={visibilityDoc}
          onClose={() => setVisibilityDoc(null)}
          onSaved={loadDocuments}
        />
      )}

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

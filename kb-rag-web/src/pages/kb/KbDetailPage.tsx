// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  DeleteOutlined,
  PlusOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Popconfirm,
  Space,
  Drawer,
  Descriptions,
  Tabs,
  Typography,
  message,
} from 'antd';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { knowledgeTodoTarget } from './knowledgeTodoTarget';
import {
  approveDocument,
  deleteDocument,
  listDocuments,
  reindexDocument,
  submitDocumentReview,
} from '../../api/document';
import {
  batchDeleteDocuments,
  batchReindexDocuments,
  confirmKbDocuments,
  getKnowledgeBase,
  getRebuildStatus,
  rebuildKb,
  updateKbGovernance,
} from '../../api/kb';
import type { KbDocument, KnowledgeBase, RebuildStatus } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import { useResourceVisit } from '../../hooks/useResourceVisit';
import PageHeader from '../../components/PageHeader';
import DocumentActions from './components/DocumentActions';
import DocumentList from './components/DocumentList';
import DocumentUploadDrawer from './components/DocumentUploadDrawer';
import DocumentFilterBar, { type DocumentFilters } from './components/DocumentFilterBar';
import KbSettingsDrawer from './components/KbSettingsDrawer';
import ChatImportWizard from './components/ChatImportWizard';
import ChunkDrawer from './components/ChunkDrawer';
import ExternalSourceTab from './components/ExternalSourceTab';
import FeedbackTab from './components/FeedbackTab';
import EmployeeFeedbackTab from './quality/EmployeeFeedbackTab';
import { RejectModal, ValidityModal } from './components/GovernanceModals';
import GraphTab from './components/GraphTab';
import IndexConfigDrawer from './components/IndexConfigDrawer';
import InsightTab from './components/InsightTab';
import ParsePreviewDrawer from './components/ParsePreviewDrawer';
import TrashTab from './components/TrashTab';
import VersionDrawer from './components/VersionDrawer';
import VisibilityDrawer from './components/VisibilityDrawer';
import WebSourcesTab from './components/WebSourcesTab';
import QualityIssueDrawer from './quality/QualityIssueDrawer';
import QualityIssueTab from './quality/QualityIssueTab';
import './quality/quality-issues.css';

// 活跃任务每 3 秒同步；空闲降低频率、失败退避，隐藏页面暂停。任务状态仍以服务端为准。
const POLL_INTERVAL_MS = 3000;
const IDLE_POLL_INTERVAL_MS = 30000;
const MAX_POLL_INTERVAL_MS = 60000;
const PROCESSING_STATES = new Set(['UPLOADED', 'PARSING', 'PARSED', 'INDEXING']);

// 前端始终在 loadDocuments 里显式带上 size，服务端不会回落到自己的默认值，所以这里的默认页大小
// 可独立于 DocumentController 的 DEFAULT_PAGE_SIZE 设置；取 10 是控制台列表的默认观感，可选
// 10/20/30/50 均在服务端 MAX_PAGE_SIZE(200) 之内。
const DEFAULT_DOC_PAGE_SIZE = 10;

export default function KbDetailPage() {
  const { kbId } = useParams<{ kbId: string }>();
  const { search } = useLocation();
  const todoTarget = useMemo(() => knowledgeTodoTarget(search), [search]);
  const navigate = useNavigate();
  const { can } = useAuth();
  // M16: the visibility editor writes through a doc:review endpoint, so only reviewers get it.
  const canDocReview = can(PERMISSIONS.DOC_REVIEW);
  const canDocWrite = can(PERMISSIONS.DOC_WRITE);
  const canKbWrite = can(PERMISSIONS.KB_WRITE);
  const canFeedback = can(PERMISSIONS.FEEDBACK_MANAGE);
  const canInsight = canFeedback || can(PERMISSIONS.AUDIT_READ);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [reviewDoc, setReviewDoc] = useState<KbDocument | null>(null);
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [documents, setDocuments] = useState<KbDocument[]>([]);
  const [docPage, setDocPage] = useState(1);
  const [docPageSize, setDocPageSize] = useState(DEFAULT_DOC_PAGE_SIZE);
  const [docTotal, setDocTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [docError, setDocError] = useState(false);
  const [docRefreshing, setDocRefreshing] = useState(false);
  const [docUpdatedAt, setDocUpdatedAt] = useState<string | null>(null);
  const [docFiltered, setDocFiltered] = useState(false);
  const [kbError, setKbError] = useState(false);
  const [rebuildError, setRebuildError] = useState(false);
  const [pollReady, setPollReady] = useState(false);
  const [activeTab, setActiveTab] = useState('documents');
  const [pageVisible, setPageVisible] = useState(document.visibilityState !== 'hidden');
  const [chunkDoc, setChunkDoc] = useState<KbDocument | null>(null);
  const [previewDoc, setPreviewDoc] = useState<KbDocument | null>(null);
  const [versionDocId, setVersionDocId] = useState<string | null>(null);
  const [chatImportOpen, setChatImportOpen] = useState(false);
  const [indexConfigOpen, setIndexConfigOpen] = useState(false);
  // 追平状态由服务端派生，null 表示还没拉到第一份（首屏别急着下"无需重建"的结论）
  const [rebuildStatus, setRebuildStatus] = useState<RebuildStatus | null>(null);
  // 仅用于按钮的即时反馈：提交请求在飞的那一小段，服务端还看不到 in_progress
  const [rebuildSubmitting, setRebuildSubmitting] = useState(false);
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
  const [qualitySelection, setQualitySelection] = useState<{ kbId: string; issueId: string }>();
  const qualityOpener = useRef<HTMLElement | null>(null);
  const [qualityRefresh, setQualityRefresh] = useState(0);
  useEffect(() => { setQualitySelection(undefined); qualityOpener.current = null; }, [kbId]);
  const openQualityIssue = (issueId: string) => {
    if (!kbId) return;
    qualityOpener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setQualitySelection({ kbId, issueId });
  };
  const closeQualityIssue = () => {
    const opener = qualityOpener.current;
    setQualitySelection(undefined);
    setQualityRefresh((value) => value + 1);
    requestAnimationFrame(() => { if (opener?.isConnected) opener.focus(); });
  };
  const readInFlight = useRef(0);
  const docFailures = useRef(0);
  const rebuildFailures = useRef(0);
  const kbSequence = useRef(0);
  const rebuildSequence = useRef(0);
  const prevStaleCountRef = useRef<number | null>(null);
  const resumePolling = useRef(false);

  useResourceVisit('KB', kbId, Boolean(kb?.kb_id === kbId && !kbError));

  const loadKb = useCallback(async () => {
    if (!kbId) return;
    const sequence = ++kbSequence.current;
    try {
      const detail = await getKnowledgeBase(kbId);
      if (sequence !== kbSequence.current) return;
      setKb(detail);
      setKbError(false);
    } catch {
      if (sequence === kbSequence.current) setKbError(true);
    }
  }, [kbId]);

  const loadRebuildStatus = useCallback(async () => {
    if (!kbId) return;
    const sequence = ++rebuildSequence.current;
    readInFlight.current += 1;
    try {
      const status = await getRebuildStatus(kbId);
      if (sequence !== rebuildSequence.current) return;
      setRebuildStatus(status);
      setRebuildError(false);
      rebuildFailures.current = 0;
    } catch {
      if (sequence === rebuildSequence.current) {
        setRebuildStatus(null);
        setRebuildError(true);
        rebuildFailures.current += 1;
      }
    } finally {
      readInFlight.current -= 1;
    }
  }, [kbId]);

  /**
   * 翻页状态放在 ref 里而不是进 useCallback 依赖：这个函数有二十余处无参调用（上传、删除、
   * 重建、各 Tab 的回调），若依赖 page 就会在每次翻页时重建，连带把挂载时那个 useEffect
   * 再跑一遍。无参调用的语义因此是"刷新当前页"，翻页则显式传页码。
   */
  const docPageRef = useRef(1);
  const docPageSizeRef = useRef(DEFAULT_DOC_PAGE_SIZE);
  const docRequestSequence = useRef(0);
  const docFiltersRef = useRef<DocumentFilters>({});

  const loadDocuments = useCallback(
    async (page?: number, size?: number, background = false) => {
      if (!kbId) return;
      const targetPage = page ?? docPageRef.current;
      const targetSize = size ?? docPageSizeRef.current;
      const sequence = ++docRequestSequence.current;
      // 翻页意图立即供轮询读取；旧页的慢响应不能覆盖新页或恢复旧页勾选。
      docPageRef.current = targetPage;
      docPageSizeRef.current = targetSize;
      setDocRefreshing(true);
      if (!background) setLoading(true);
      readInFlight.current += 1;
      try {
        const result = await listDocuments(kbId, { ...docFiltersRef.current, page: targetPage, size: targetSize });
        if (sequence !== docRequestSequence.current) return;
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
        setDocError(false);
        docFailures.current = 0;
        setDocUpdatedAt(new Date().toLocaleTimeString('zh-CN', { hour12: false }));
        // 文档可能被其他操作员移出当前筛选结果，勾选范围始终以最新一页为准。
        setSelectedDocIds((ids) => ids.filter((id) => result.items.some((doc) => doc.doc_id === id)));
      } catch {
        if (sequence === docRequestSequence.current) {
          setDocError(true);
          docFailures.current += 1;
          setSelectedDocIds([]);
        }
      } finally {
        readInFlight.current -= 1;
        if (sequence === docRequestSequence.current) {
          setLoading(false);
          setDocRefreshing(false);
        }
      }
    },
    [kbId],
  );

  useEffect(() => {
    if (!kbId) return;
    let mounted = true;
    setPollReady(false);
    docFiltersRef.current = todoTarget.filters;
    docPageRef.current = 1;
    docPageSizeRef.current = DEFAULT_DOC_PAGE_SIZE;
    docFailures.current = 0;
    rebuildFailures.current = 0;
    prevStaleCountRef.current = null;
    setDocFiltered(Object.keys(todoTarget.filters).length > 0);
    setDocError(false);
    setDocUpdatedAt(null);
    setDocuments([]);
    setSelectedDocIds([]);
    setKb(null);
    setRebuildStatus(null);
    setActiveTab(todoTarget.tab);
    Promise.all([loadKb(), loadDocuments(), loadRebuildStatus()]).then(() => {
      if (mounted) setPollReady(true);
    });
    return () => {
      mounted = false;
      docRequestSequence.current += 1;
      kbSequence.current += 1;
      rebuildSequence.current += 1;
    };
  }, [kbId, loadKb, loadDocuments, loadRebuildStatus, todoTarget]);

  useEffect(() => {
    const onVisibility = () => {
      const visible = document.visibilityState !== 'hidden';
      resumePolling.current = visible;
      setPageVisible(visible);
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  const hasProcessing = (rebuildStatus?.processing_count ?? rebuildStatus?.in_progress_count ?? 0) > 0
    || documents.some((doc) => PROCESSING_STATES.has(doc.process_status));
  const pollInterval = hasProcessing ? POLL_INTERVAL_MS : IDLE_POLL_INTERVAL_MS;
  useEffect(() => {
    if (!pollReady || !pageVisible || activeTab !== 'documents') return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const schedule = (immediate = false) => {
      if (stopped) return;
      const failures = Math.max(docFailures.current, rebuildFailures.current);
      const delay = Math.min(MAX_POLL_INTERVAL_MS, pollInterval * 2 ** Math.min(failures, 4));
      timer = setTimeout(async () => {
        // 上一轮请求完成后再计时；手动刷新在飞时也不叠加自动请求。
        if (readInFlight.current === 0) {
          await Promise.all([loadDocuments(undefined, undefined, true), loadRebuildStatus()]);
        }
        schedule();
      }, immediate ? 0 : delay);
    };
    schedule(resumePolling.current);
    resumePolling.current = false;
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [activeTab, pageVisible, pollReady, pollInterval, loadDocuments, loadRebuildStatus]);

  const staleCount = rebuildStatus?.stale_count ?? 0;
  const documentCount = rebuildStatus?.document_count ?? (!docFiltered && !docError ? docTotal : undefined);
  const rebuildInProgress = (rebuildStatus?.in_progress_count ?? 0) > 0;
  const rebuildFailedCount = rebuildStatus?.failed_count ?? 0;
  // 排队中 = 待追平里既没在跑、也没失败的那部分：线程池并发有限，提交一批后大多数文档在这里等着
  const rebuildQueuedCount = Math.max(
    0,
    staleCount - (rebuildStatus?.in_progress_count ?? 0) - rebuildFailedCount,
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

  /**
   * 待追平数从有到无就是重建收尾。只提示一次、且只在本次驻留期间观察到这个跳变时提示——离开又回来
   * 时上一份计数不可知，此时告警条直接消失本身就是完成信号，硬补一句 toast 反而像凭空冒出来。
   * 重建失败的文档仍是 stale，所以计数不会归零，不存在把失败说成成功的路径。
   */
  useEffect(() => {
    if (!rebuildStatus) return;
    const prev = prevStaleCountRef.current;
    prevStaleCountRef.current = rebuildStatus.stale_count;
    if (prev !== null && prev > 0 && rebuildStatus.stale_count === 0) {
      message.success('重建完成，新分片配置已生效');
    }
  }, [rebuildStatus]);

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

  /**
   * 提交整库追平。不传 doc_ids 让服务端自己圈定全部 config_stale 文档：早先传的是当前页那几篇，
   * 翻页外的待重建文档因此永远追不平。
   */
  const handleRebuildStale = async () => {
    if (!kbId || staleCount === 0) return;
    setRebuildSubmitting(true);
    try {
      await rebuildKb(kbId);
      message.success('已提交按新配置重建任务');
      await Promise.all([loadDocuments(), loadRebuildStatus()]);
    } finally {
      setRebuildSubmitting(false);
    }
  };

  const handleIndexConfigSaved = () => {
    loadKb();
    loadDocuments();
    loadRebuildStatus();
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
      message.success(
        checked
          ? '已开启审核：之后上传的新文档需审核通过后才参与检索'
          : '已关闭审核：之后上传的新文档直接发布',
      );
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

  return (
    <div className="knowledge-workbench-page kb-detail-page">
      <PageHeader
        eyebrow="KNOWLEDGE OPERATIONS"
        title={kb?.name ?? '知识库详情'}
        description={kb?.description || '管理文档生命周期、索引策略、知识图谱与检索质量。'}
        before={
          <Button
            className="page-back-button"
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/kb')}
          >
            返回知识库
          </Button>
        }
        actions={
          <Space className="kb-detail-actions" wrap>
            {canKbWrite && (
              <Button icon={<SettingOutlined />} onClick={() => setSettingsOpen(true)}>
                知识库设置
              </Button>
            )}
            {canDocWrite && (
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadOpen(true)}>
                添加文档
              </Button>
            )}
          </Space>
        }
      />

      {kbError && <Alert type="error" showIcon message="知识库信息加载失败"
        action={<Button aria-label="重试知识库信息" onClick={() => { void loadKb(); }}>重试</Button>} />}
      <Tabs
        className="kb-detail-tabs"
        activeKey={activeTab}
        onChange={(key) => { resumePolling.current = key === 'documents'; setActiveTab(key); }}
        items={[
          {
            key: 'documents',
            label: `文档${loading || documentCount === undefined ? '' : `（${documentCount}）`}`,
            children: (
              <>
                <DocumentFilterBar
                  key={`${kbId}:${todoTarget.kind}`}
                  initialFilters={todoTarget.filters}
                  onApply={(filters) => {
                    docFiltersRef.current = filters;
                    setDocFiltered(Object.values(filters).some((value) => value !== undefined));
                    setSelectedDocIds([]);
                    void loadDocuments(1);
                  }}
                  onRefresh={() => { void loadDocuments(); }}
                  refreshing={docRefreshing}
                />
                {docUpdatedAt && <Typography.Paragraph type="secondary">
                  文档列表更新于 {docUpdatedAt}
                  {rebuildStatus?.processing_count !== undefined && ` · 全库 ${rebuildStatus.processing_count} 篇处理中`}
                </Typography.Paragraph>}
                {docError && <Alert
                  type="error" showIcon message="文档加载失败"
                  description="筛选条件已保留，请重试。"
                  action={<Button aria-label="重试" onClick={() => { void loadDocuments(); }} loading={docRefreshing}>重试</Button>}
                  style={{ marginBottom: 16 }}
                />}
                {rebuildError && <Alert type="warning" showIcon message="处理进度暂不可用"
                  description="文档任务仍在服务端运行，恢复连接后会重新同步状态。"
                  action={<Button aria-label="重试处理进度" onClick={() => { void loadRebuildStatus(); }}>重试</Button>}
                  style={{ marginBottom: 16 }} />}
                {staleCount > 0 && (
                  <Alert
                    type="warning"
                    showIcon
                    message={`${staleCount} 篇文档使用旧配置`}
                    description={
                      rebuildInProgress ? (
                        <>
                          <Typography.Text type="secondary">
                            {`正在按新配置重建：${rebuildStatus?.in_progress_count} 篇处理中`}
                            {rebuildQueuedCount > 0 ? `，${rebuildQueuedCount} 篇排队中` : ''}
                            {rebuildFailedCount > 0 ? `，${rebuildFailedCount} 篇失败` : ''}
                          </Typography.Text>
                        </>
                      ) : rebuildFailedCount > 0 ? (
                        `${rebuildFailedCount} 篇文档重建失败，可在下方列表查看失败原因后重试；其余待重建文档可再次提交`
                      ) : (
                        '索引配置已变更，需要按新配置重建后才能生效'
                      )
                    }
                    action={
                      canKbWrite && (
                        <Button
                          size="small"
                          type="primary"
                          loading={rebuildSubmitting || rebuildInProgress}
                          disabled={rebuildSubmitting || rebuildInProgress}
                          onClick={handleRebuildStale}
                        >
                          {rebuildInProgress ? '重建中' : '按新配置重建'}
                        </Button>
                      )
                    }
                    style={{ marginBottom: 16 }}
                  />
                )}

                {!docError && pendingConfirmDocs.length > 0 && (
                  <Alert
                    type="info"
                    showIcon
                    message={`${pendingConfirmDocs.length} 篇文档待预览确认`}
                    description="已开启解析预览确认，文档清洗完成后会暂停在此状态；可逐篇预览后确认，或直接批量确认全部"
                    action={
                      canDocWrite && (
                        <Button
                          size="small"
                          type="primary"
                          icon={<CheckOutlined />}
                          loading={batchConfirming}
                          onClick={handleBatchConfirm}
                        >
                          批量确认
                          {selectedPendingConfirmIds.length > 0
                            ? `（${selectedPendingConfirmIds.length}）`
                            : '（全部）'}
                        </Button>
                      )
                    }
                    style={{ marginBottom: 16 }}
                  />
                )}

                {canDocWrite && selectedDocIds.length > 0 && (
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

                {!docError && <DocumentList
                  documents={documents}
                  loading={loading}
                  filtered={docFiltered}
                  page={docPage}
                  pageSize={docPageSize}
                  total={docTotal}
                  selectedIds={selectedDocIds}
                  canSelect={canDocWrite}
                  onSelect={setSelectedDocIds}
                  onPageChange={(page, size) => {
                    setSelectedDocIds([]);
                    void loadDocuments(page, size);
                  }}
                  actions={(doc) => (
                    <DocumentActions
                      doc={doc}
                      canWrite={canDocWrite}
                      canReview={canDocReview}
                      deleting={deletingId === doc.doc_id}
                      onView={() => setChunkDoc(doc)}
                      onVersions={() => setVersionDocId(doc.doc_id)}
                      onPreview={() => setPreviewDoc(doc)}
                      onReview={() => setReviewDoc(doc)}
                      onSubmitReview={() => handleSubmitReview(doc)}
                      onValidity={() => setValidityDoc(doc)}
                      onVisibility={() => setVisibilityDoc(doc)}
                      onReindex={() => handleReindex(doc.doc_id)}
                      onDelete={() => handleDelete(doc)}
                    />
                  )}
                />}
              </>
            ),
          },
          {
            key: 'sources',
            label: '数据来源',
            children: kbId ? (
              <Tabs
                key={`${kbId}:${todoTarget.kind}`}
                defaultActiveKey={todoTarget.sourceTab}
                className="workspace-secondary-tabs"
                items={[
                  {
                    key: 'webSources',
                    label: '网页导入',
                    children: <WebSourcesTab kbId={kbId} onSynced={loadDocuments} initialAttentionOnly={todoTarget.kind === 'WEB_SOURCE_FAILED'} />,
                  },
                  {
                    key: 'extSources',
                    label: '外部数据源',
                    children: <ExternalSourceTab kbId={kbId} onSynced={loadDocuments} initialAttentionOnly={todoTarget.kind === 'EXT_SOURCE_ATTENTION'} />,
                  },
                  ...(canDocWrite
                    ? [
                        {
                          key: 'chatImport',
                          label: '聊天导入',
                          children: (
                            <div className="workspace-intro-panel">
                              <Typography.Title level={5}>导入聊天记录</Typography.Title>
                              <Typography.Paragraph type="secondary">
                                预览字段与聚合方式后再确认导入。
                              </Typography.Paragraph>
                              <Button type="primary" onClick={() => setChatImportOpen(true)}>
                                导入聊天记录
                              </Button>
                            </div>
                          ),
                        },
                      ]
                    : []),
                ]}
              />
            ) : null,
          },
          {
            key: 'graph',
            label: '知识图谱',
            children: kbId ? <GraphTab kbId={kbId} kb={kb} onKbChanged={loadKb} /> : null,
          },
          ...(canFeedback || canInsight
            ? [
                {
                  key: 'quality',
                  label: '质量与反馈',
                  children: kbId ? (
                    <Tabs
                      className="workspace-secondary-tabs"
                      items={[
                        ...(canFeedback
                          ? [{ key: 'issues', label: '质量问题', children: <QualityIssueTab key={kbId} kbId={kbId} refreshKey={qualityRefresh} onOpen={openQualityIssue} /> }]
                          : []),
                        ...(canFeedback
                          ? [{ key: 'feedback', label: '反馈管理', children: <Tabs items={[
                            { key: 'retrieval', label: '检索反馈', children: <FeedbackTab kbId={kbId} onOpenIssue={openQualityIssue} /> },
                            ...(can(PERMISSIONS.APP_READ) ? [{ key: 'employee', label: '员工问答反馈', children: <EmployeeFeedbackTab kbId={kbId} onOpenIssue={openQualityIssue} /> }] : []),
                          ]} /> }]
                          : []),
                        ...(canInsight
                          ? [{ key: 'insight', label: '检索洞察', children: <InsightTab kbId={kbId} onOpenIssue={canFeedback ? openQualityIssue : undefined} /> }]
                          : []),
                      ]}
                    />
                  ) : null,
                },
              ]
            : []),
          ...(canDocWrite || canDocReview
            ? [
                {
                  key: 'trash',
                  label: '回收站',
                  children: kbId ? <TrashTab kbId={kbId} onRestored={loadDocuments} /> : null,
                },
              ]
            : []),
        ]}
      />

      {canFeedback && kbId && qualitySelection?.kbId === kbId && <QualityIssueDrawer key={`${kbId}:${qualitySelection.issueId}`} kbId={kbId} issueId={qualitySelection.issueId}
        onClose={closeQualityIssue}
        onChanged={() => setQualityRefresh((value) => value + 1)} />}

      {canDocWrite && kbId && <DocumentUploadDrawer kbId={kbId} open={uploadOpen}
        onClose={() => setUploadOpen(false)} onAccepted={loadDocuments} />}
      {canKbWrite && settingsOpen && kb && (
        <KbSettingsDrawer
          kb={kb}
          onClose={() => setSettingsOpen(false)}
          onSaved={loadKb}
          onIndexConfig={() => {
            setSettingsOpen(false);
            setIndexConfigOpen(true);
          }}
          onGovernance={handleGovernanceToggle}
          governanceSaving={governanceSaving}
        />
      )}
      {canDocReview && (
        <Drawer
          title="审核文档"
          open={Boolean(reviewDoc)}
          width={580}
          onClose={() => setReviewDoc(null)}
          footer={
            reviewDoc && (
              <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
                <Button
                  danger
                  onClick={() => {
                    setRejectDoc(reviewDoc);
                    setReviewDoc(null);
                  }}
                >
                  驳回
                </Button>
                <Popconfirm
                  title="通过审核并发布？"
                  description="发布后文档将参与检索；下架需设置失效时间或移入回收站。"
                  okText="通过并发布"
                  cancelText="取消"
                  onConfirm={async () => {
                    await handleApprove(reviewDoc);
                    setReviewDoc(null);
                  }}
                >
                  <Button type="primary">通过并发布</Button>
                </Popconfirm>
              </Space>
            )
          }
        >
          {reviewDoc && (
            <>
              <Descriptions
                column={1}
                items={[
                  { key: 'name', label: '文档', children: reviewDoc.file_name },
                  { key: 'process', label: '处理状态', children: reviewDoc.process_status },
                  { key: 'publish', label: '发布状态', children: '待审核' },
                  { key: 'note', label: '审核说明', children: reviewDoc.review_note || '暂无' },
                ]}
              />
              <Button onClick={() => setChunkDoc(reviewDoc)}>查看文档分片</Button>
            </>
          )}
        </Drawer>
      )}

      <ChunkDrawer
        docId={chunkDoc?.doc_id ?? null}
        docName={chunkDoc?.file_name ?? null}
        onClose={() => setChunkDoc(null)}
      />

      {previewDoc && canDocWrite && (
        <ParsePreviewDrawer
          key={previewDoc.doc_id}
          doc={previewDoc}
          onClose={() => setPreviewDoc(null)}
          onConfirmed={handlePreviewConfirmed}
        />
      )}

      <VersionDrawer
        doc={versionDoc}
        onClose={() => setVersionDocId(null)}
        onActivated={handleVersionActivated}
      />

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

      {kbId && canDocWrite && chatImportOpen && (
        <ChatImportWizard
          kbId={kbId}
          open={chatImportOpen}
          onClose={() => setChatImportOpen(false)}
          onImported={handleChatImported}
        />
      )}

      {kbId && canKbWrite && indexConfigOpen && (
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

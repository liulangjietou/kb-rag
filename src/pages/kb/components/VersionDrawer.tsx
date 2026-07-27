// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  List,
  Modal,
  Popconfirm,
  Progress,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { listPendingReviewAnnotations, migrateAnnotation } from '../../../api/annotation';
import { activateVersion, getActivateImpact, listDocumentVersions } from '../../../api/version';
import type { ActivateImpact, DocumentVersion, KbDocument, PendingAnnotation } from '../../../api/types';
import { formatPercent } from '../../../utils/format';
import {
  ANNOTATION_TYPE_META,
  DOCUMENT_VERSION_STATUS_META,
  INHERIT_STATUS_META,
  PROCESS_STATUS_META,
  ROLLBACK_MODE_META,
  metaOf,
} from '../../../utils/statusMeta';

// REBUILD-mode completion is polled at the same 3s cadence KbDetailPage already uses for its
// own document list, so a fresh rebuild feels no slower than the existing "按新配置重建" flow.
const REBUILD_POLL_INTERVAL_MS = 3000;

interface VersionDrawerProps {
  /**
   * The document whose versions are being managed; drawer is closed when null. Only used for the
   * title and the (cosmetic) process_status hint -- version data itself always comes from this
   * drawer's own listDocumentVersions/listPendingReviewAnnotations calls, not from this prop.
   */
  doc: KbDocument | null;
  onClose: () => void;
  /** Called after a successful activation (instant, or a rebuild that finished) so the caller can refresh the document list. */
  onActivated: () => void;
}

interface ImpactModalState {
  version: DocumentVersion;
  impact: ActivateImpact;
}

/**
 * Document version management drawer (M4a-CONTRACTS.md sections 1.2/3): version list with an
 * activate action per row, an activate-impact confirm dialog (rollback_mode + stale annotation
 * warning), REBUILD-mode progress polling, and a top-of-drawer pending-review alert for
 * old-version annotations that were not carried over automatically.
 */
export default function VersionDrawer({ doc, onClose, onActivated }: VersionDrawerProps) {
  const docId = doc?.doc_id ?? null;
  const [versions, setVersions] = useState<DocumentVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [pendingAnnotations, setPendingAnnotations] = useState<PendingAnnotation[]>([]);
  const [pendingLoading, setPendingLoading] = useState(false);
  const [pendingExpanded, setPendingExpanded] = useState(false);
  const [migratingKey, setMigratingKey] = useState<string | null>(null);
  const [impactLoadingVersionId, setImpactLoadingVersionId] = useState<string | null>(null);
  const [impactModal, setImpactModal] = useState<ImpactModalState | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [rebuildingVersionId, setRebuildingVersionId] = useState<string | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadVersions = useCallback(async (): Promise<DocumentVersion[] | undefined> => {
    if (!docId) return undefined;
    setLoading(true);
    try {
      const result = await listDocumentVersions(docId);
      setVersions(result);
      return result;
    } finally {
      setLoading(false);
    }
  }, [docId]);

  const loadPending = useCallback(async () => {
    if (!docId) return;
    setPendingLoading(true);
    try {
      const result = await listPendingReviewAnnotations(docId);
      setPendingAnnotations(result);
    } finally {
      setPendingLoading(false);
    }
  }, [docId]);

  /**
   * M9-CONTRACTS.md section 0.5: apply the pending row's edit/disable semantics onto the operator-
   * confirmed target chunk, then reload the pending list -- migrate is idempotent server-side but
   * the row still needs to disappear from this list once processed, which only a reload guarantees
   * (the response shape carries no fields this UI needs to trust beyond success/failure).
   */
  const handleMigrate = async (annotationId: string, targetChunkId: string) => {
    setMigratingKey(`${annotationId}:${targetChunkId}`);
    try {
      await migrateAnnotation(annotationId, { target_chunk_id: targetChunkId });
      message.success('已迁移到目标分片');
      await loadPending();
    } finally {
      setMigratingKey(null);
    }
  };

  useEffect(() => {
    if (!docId) {
      setVersions([]);
      setPendingAnnotations([]);
      setPendingExpanded(false);
      setImpactModal(null);
      setRebuildingVersionId(null);
      return;
    }
    loadVersions();
    loadPending();
  }, [docId, loadVersions, loadPending]);

  // Poll the version list itself for REBUILD completion -- the target version's own `status`
  // field flipping to ACTIVE/BUILD_FAILED is the authoritative signal, independent of whatever
  // the owning document's process_status happens to report during the rebuild task.
  useEffect(() => {
    if (!rebuildingVersionId) {
      return;
    }
    pollTimerRef.current = setInterval(async () => {
      const latest = await loadVersions();
      const target = latest?.find((v) => v.version_id === rebuildingVersionId);
      if (target?.status === 'ACTIVE') {
        message.success('重建完成，已切换到该版本');
        setRebuildingVersionId(null);
        onActivated();
      } else if (target?.status === 'BUILD_FAILED') {
        message.error('重建失败，请重试或联系管理员');
        setRebuildingVersionId(null);
      }
    }, REBUILD_POLL_INTERVAL_MS);
    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
      }
    };
  }, [rebuildingVersionId, loadVersions, onActivated]);

  const openImpact = async (version: DocumentVersion) => {
    if (!docId) return;
    setImpactLoadingVersionId(version.version_id);
    try {
      const impact = await getActivateImpact(docId, version.version_id);
      setImpactModal({ version, impact });
    } finally {
      setImpactLoadingVersionId(null);
    }
  };

  const handleConfirmActivate = async () => {
    if (!docId || !impactModal) return;
    setConfirming(true);
    try {
      const response = await activateVersion(docId, impactModal.version.version_id);
      if (response.task_id) {
        setRebuildingVersionId(impactModal.version.version_id);
        message.info('已提交重建任务，完成后自动切换');
      } else {
        message.success('已切换激活版本');
        onActivated();
      }
      setImpactModal(null);
      loadVersions();
    } finally {
      setConfirming(false);
    }
  };

  const pendingCount = pendingAnnotations.length;

  return (
    <Drawer title={`版本管理${doc ? ` - ${doc.file_name}` : ''}`} open={doc !== null} onClose={onClose} width={760} destroyOnClose>
      <Spin spinning={loading}>
        {pendingCount > 0 && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message={`旧版本有 ${pendingCount} 处人工标注未在新版本重做`}
            description={
              <>
                <Button size="small" type="link" style={{ padding: 0, height: 'auto' }} onClick={() => setPendingExpanded((prev) => !prev)}>
                  {pendingExpanded ? '收起清单' : '展开清单'}
                </Button>
                {pendingExpanded && (
                  <List
                    size="small"
                    loading={pendingLoading}
                    dataSource={pendingAnnotations}
                    style={{ marginTop: 8 }}
                    renderItem={(item) => {
                      const typeMeta = metaOf(ANNOTATION_TYPE_META, item.annotation_type);
                      const inheritMeta = metaOf(INHERIT_STATUS_META, item.inherit_status);
                      const excerpt = item.payload.after_excerpt ?? item.payload.before_excerpt;
                      return (
                        <List.Item key={item.annotation_id}>
                          <Space direction="vertical" size={4} style={{ width: '100%' }}>
                            <Space wrap>
                              <Tag color={typeMeta.color}>{typeMeta.label}</Tag>
                              <Tag color={inheritMeta.color}>{inheritMeta.label}</Tag>
                              <Typography.Text type="secondary">分片: {item.chunk_id}</Typography.Text>
                            </Space>
                            {excerpt && (
                              <Typography.Paragraph
                                type="secondary"
                                style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
                                ellipsis={{ rows: 2, expandable: true, symbol: '展开' }}
                              >
                                {excerpt}
                              </Typography.Paragraph>
                            )}
                            {/*
                              M9-CONTRACTS.md section 0.5:辅助迁移建议 -- lazily computed per
                              request, top-3 candidates in the newly active version ranked by
                              symmetric 3-gram Dice similarity. Short excerpts (<10 normalized
                              chars) or no candidate clearing the 0.35 floor come back as an empty
                              array, rendered as the explicit "无相似候选" hint rather than nothing.
                            */}
                            {item.suggestions.length === 0 ? (
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                无相似候选（可能为短文本或改动过大）
                              </Typography.Text>
                            ) : (
                              <List
                                size="small"
                                dataSource={item.suggestions}
                                style={{ width: '100%' }}
                                renderItem={(suggestion) => {
                                  const migrateKey = `${item.annotation_id}:${suggestion.chunk_id}`;
                                  return (
                                    <List.Item
                                      key={suggestion.chunk_id}
                                      style={{ paddingLeft: 0, paddingRight: 0 }}
                                      actions={[
                                        <Popconfirm
                                          key="migrate"
                                          title="迁移到此分片？"
                                          description="将该标注应用到此分片，原待复核记录将置为已处理"
                                          okText="迁移"
                                          cancelText="取消"
                                          onConfirm={() => handleMigrate(item.annotation_id, suggestion.chunk_id)}
                                        >
                                          <Button size="small" type="link" loading={migratingKey === migrateKey}>
                                            迁移到此分片
                                          </Button>
                                        </Popconfirm>,
                                      ]}
                                    >
                                      <Space direction="vertical" size={0}>
                                        <Space wrap>
                                          <Tag color="cyan">相似度 {formatPercent(suggestion.score)}</Tag>
                                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                            分片: {suggestion.chunk_id}
                                          </Typography.Text>
                                        </Space>
                                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                          {suggestion.content_preview}
                                        </Typography.Text>
                                      </Space>
                                    </List.Item>
                                  );
                                }}
                              />
                            )}
                          </Space>
                        </List.Item>
                      );
                    }}
                  />
                )}
              </>
            }
          />
        )}

        {rebuildingVersionId && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="REBUILD 任务进行中，完成后自动切换激活版本"
            description={
              <Space direction="vertical" style={{ width: '100%' }}>
                <Progress percent={99} status="active" showInfo={false} size="small" />
                {doc && <Typography.Text type="secondary">文档处理状态：{metaOf(PROCESS_STATUS_META, doc.process_status).label}</Typography.Text>}
              </Space>
            }
          />
        )}

        {versions.length === 0 && !loading ? (
          <Empty description="暂无版本记录" />
        ) : (
          <Table<DocumentVersion>
            rowKey="version_id"
            size="small"
            dataSource={versions}
            pagination={false}
            columns={[
              { title: '版本号', dataIndex: 'version', width: 90 },
              {
                title: '状态',
                dataIndex: 'status',
                width: 110,
                render: (status: DocumentVersion['status']) => {
                  const meta = metaOf(DOCUMENT_VERSION_STATUS_META, status);
                  return <Tag color={meta.color}>{meta.label}</Tag>;
                },
              },
              { title: '分片数', dataIndex: 'chunk_count', width: 80 },
              { title: '创建时间', dataIndex: 'created_at', width: 170 },
              {
                title: '变更说明',
                dataIndex: 'changelog',
                render: (changelog: string | null) => changelog || '-',
              },
              {
                title: '激活',
                dataIndex: 'active',
                width: 90,
                render: (active: boolean) => (active ? <Tag color="success">当前激活</Tag> : null),
              },
              {
                // M6-CONTRACTS.md section 0.8/2: pin state from AppVersionPinChecker, display-only
                // (see DocumentVersion.pinned doc comment in api/types.ts -- archival itself has no
                // manual web entry point to disable, the server-side pin check blocks the automatic
                // retention sweep directly).
                title: '引用状态',
                width: 160,
                render: (_, record: DocumentVersion) =>
                  record.pinned ? (
                    <Tooltip
                      title={
                        record.pinned_by && record.pinned_by.length > 0
                          ? `被以下应用版本引用：${record.pinned_by.join('、')}`
                          : '被应用版本引用'
                      }
                    >
                      <Tag color="gold">被引用（保留中）</Tag>
                    </Tooltip>
                  ) : null,
              },
              {
                title: '操作',
                width: 90,
                render: (_, record: DocumentVersion) => (
                  <Button
                    size="small"
                    type="primary"
                    disabled={record.active}
                    loading={impactLoadingVersionId === record.version_id}
                    onClick={() => openImpact(record)}
                  >
                    激活
                  </Button>
                ),
              },
            ]}
          />
        )}
      </Spin>

      <Modal
        title="确认切换激活版本"
        open={impactModal !== null}
        onCancel={() => setImpactModal(null)}
        onOk={handleConfirmActivate}
        okText="确认切换"
        cancelText="取消"
        confirmLoading={confirming}
        destroyOnClose
      >
        {impactModal && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="目标版本">{impactModal.version.version}</Descriptions.Item>
              <Descriptions.Item label="切换方式">
                <Tag color={metaOf(ROLLBACK_MODE_META, impactModal.impact.rollback_mode).color}>
                  {metaOf(ROLLBACK_MODE_META, impactModal.impact.rollback_mode).label}
                </Tag>
              </Descriptions.Item>
            </Descriptions>
            {impactModal.impact.needs_rebuild && (
              <Alert type="warning" showIcon message="该版本分片已被清理，需要从解析产物重新构建，切换不会立即完成" />
            )}
            {impactModal.impact.stale_annotation_count > 0 && (
              <Alert
                type="warning"
                showIcon
                message={`切换后将有 ${impactModal.impact.stale_annotation_count} 处人工标注未继承/未重做`}
              />
            )}
            {impactModal.impact.affected_eval_case_count > 0 && (
              <Alert
                type="warning"
                showIcon
                message={`切换后将有 ${impactModal.impact.affected_eval_case_count} 条评测 case 进入待复核（evidence_stale）`}
              />
            )}
          </Space>
        )}
      </Modal>
    </Drawer>
  );
}

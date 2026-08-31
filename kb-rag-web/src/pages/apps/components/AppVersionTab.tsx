// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import {
  bindGateDataset,
  listAppVersions,
  releaseAppVersion,
  rollbackAppVersion,
  submitAppVersionForTest,
} from '../../../api/app';
import { listEvalDatasets } from '../../../api/evalDataset';
import type { AppVersion, AppVersionIndexSnapshot, EvalDataset, KnowledgeBase } from '../../../api/types';
import { APP_VERSION_STATUS_META, metaOf } from '../../../utils/statusMeta';
import { kbNameOf, resolveKbRefs } from '../../../utils/kbRefs';
import GateCompareDrawer from './GateCompareDrawer';

interface AppVersionTabProps {
  appId: string;
  kbs: KnowledgeBase[];
  onVersionsChanged: (versions: AppVersion[]) => void;
}

// Same 3s cadence used by every other in-flight-task poll in this codebase (KbDetailPage rebuild,
// VersionDrawer REBUILD activation, EvalRunTab run history).
const POLL_INTERVAL_MS = 3000;

/**
 * 版本列表 tab (M4c-CONTRACTS.md sections 2/4): eight-state status Tag, submit-test/release/
 * rollback actions, gate-dataset binding, GATING progress polling, and the GATE_LOG_ONLY
 * force-release confirmation dialog. Double-run comparison results are shown via GateCompareDrawer.
 */
export default function AppVersionTab({ appId, kbs, onVersionsChanged }: AppVersionTabProps) {
  const [versions, setVersions] = useState<AppVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [datasetsByKb, setDatasetsByKb] = useState<Map<string, EvalDataset[]>>(new Map());
  const [bindingVersionId, setBindingVersionId] = useState<string | null>(null);
  const [actingVersionId, setActingVersionId] = useState<string | null>(null);
  const [forceModalVersion, setForceModalVersion] = useState<AppVersion | null>(null);
  const [compareVersion, setCompareVersion] = useState<AppVersion | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadVersions = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listAppVersions(appId);
      setVersions(result);
      onVersionsChanged(result);
      return result;
    } finally {
      setLoading(false);
    }
  }, [appId, onVersionsChanged]);

  useEffect(() => {
    loadVersions();
  }, [loadVersions]);

  // Load the gate-dataset picker options for every distinct kb_id referenced across this app's
  // versions' kb_refs (M5: a version can span several kbs, so this is now a flatMap/union rather
  // than one id per version).
  useEffect(() => {
    const kbIds = Array.from(new Set(versions.flatMap((v) => resolveKbRefs(v.config).map((ref) => ref.kb_id))));
    kbIds.forEach((kbId) => {
      if (!datasetsByKb.has(kbId)) {
        listEvalDatasets(kbId).then((datasets) => {
          setDatasetsByKb((prev) => new Map(prev).set(kbId, datasets));
        });
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versions]);

  const hasGatingVersion = versions.some((v) => v.status === 'GATING');
  useEffect(() => {
    if (!hasGatingVersion) {
      return;
    }
    pollTimerRef.current = setInterval(() => loadVersions(), POLL_INTERVAL_MS);
    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
      }
    };
  }, [hasGatingVersion, loadVersions]);

  const handleBindDataset = async (versionId: string, datasetId: string | null) => {
    setBindingVersionId(versionId);
    try {
      await bindGateDataset(versionId, { dataset_id: datasetId });
      message.success(datasetId ? '已绑定门禁评测集' : '已解除门禁评测集绑定');
      loadVersions();
    } finally {
      setBindingVersionId(null);
    }
  };

  const handleSubmitTest = async (versionId: string) => {
    setActingVersionId(versionId);
    try {
      await submitAppVersionForTest(versionId);
      message.success('已提交测试');
      loadVersions();
    } finally {
      setActingVersionId(null);
    }
  };

  /** Appends the server's human-readable gate reason to a toast, when it sent one. */
  const reasonSuffix = (version: AppVersion) =>
    version.gate_reason_message ? `：${version.gate_reason_message}` : '';

  const handleRelease = async (versionId: string, force?: boolean) => {
    setActingVersionId(versionId);
    try {
      const updated = await releaseAppVersion(versionId, force);
      if (updated.status === 'RELEASED') {
        message.success('发布成功');
      } else if (updated.status === 'GATING') {
        message.info('已提交发布，门禁评测执行中');
      } else if (updated.status === 'GATE_LOG_ONLY') {
        // gate_reason_message is the server's own rendering of gate_reason; showing the bare
        // verdict enum instead left the operator with no way to tell *why* the gate landed here.
        message.warning(`门禁仅记录（未自动发布），可选择强制发布${reasonSuffix(updated)}`);
      } else if (updated.status === 'GATE_BLOCKED') {
        message.error(`门禁拦截，未发布${reasonSuffix(updated)}`);
      }
      setForceModalVersion(null);
      loadVersions();
    } finally {
      setActingVersionId(null);
    }
  };

  const handleRollback = async (versionId: string) => {
    setActingVersionId(versionId);
    try {
      await rollbackAppVersion(versionId);
      message.success('已提交回滚');
      loadVersions();
    } finally {
      setActingVersionId(null);
    }
  };

  return (
    <div className="catalog-data-surface">
      <Table<AppVersion>
        rowKey="app_version_id"
        loading={loading}
        dataSource={versions}
        pagination={false}
        columns={[
          { title: '版本号', dataIndex: 'version', width: 90 },
          {
            title: '状态',
            dataIndex: 'status',
            width: 130,
            render: (status: AppVersion['status'], record: AppVersion) => {
              const meta = metaOf(APP_VERSION_STATUS_META, status);
              const tag = <Tag color={meta.color}>{meta.label}</Tag>;
              const hint = record.gate_reason_message ?? record.gate_verdict;
              return hint ? <span title={hint}>{tag}</span> : tag;
            },
          },
          {
            title: '门禁评测集',
            width: 220,
            render: (_, record: AppVersion) => {
              const canBind = record.status === 'DRAFT' || record.status === 'TESTING';
              // M5: a version may span several kbs (M4b's eval dataset is still single-kb, per
              // M5-CONTRACTS.md section 2.2 "评测不涉及多库，不改"), so the picker offers the
              // union of every referenced kb's datasets, prefixed with the kb name once there is
              // more than one to disambiguate same-named datasets across kbs.
              const refs = resolveKbRefs(record.config);
              const options = refs.flatMap((ref) =>
                (datasetsByKb.get(ref.kb_id) ?? []).map((d) => ({
                  label: refs.length > 1 ? `${kbNameOf(kbs, ref.kb_id)}·${d.name}` : d.name,
                  value: d.dataset_id,
                })),
              );
              return (
                <Select
                  size="small"
                  style={{ width: 200 }}
                  allowClear
                  disabled={!canBind}
                  loading={bindingVersionId === record.app_version_id}
                  placeholder="未绑定（发布将仅记录）"
                  value={record.gate_dataset_id ?? undefined}
                  options={options}
                  onChange={(value) => handleBindDataset(record.app_version_id, value ?? null)}
                  onClear={() => handleBindDataset(record.app_version_id, null)}
                />
              );
            },
          },
          { title: '变更说明', dataIndex: 'changelog', render: (changelog: string | null) => changelog || '-' },
          { title: '创建时间', dataIndex: 'created_at', width: 170 },
          {
            title: '操作',
            width: 300,
            render: (_, record: AppVersion) => {
              const acting = actingVersionId === record.app_version_id;
              const hasCompareResult = (record.gate_run_ids?.length ?? 0) > 0;
              return (
                <Space wrap>
                  {record.status === 'DRAFT' && (
                    <Button size="small" loading={acting} onClick={() => handleSubmitTest(record.app_version_id)}>
                      提交测试
                    </Button>
                  )}
                  {(record.status === 'TESTING' || record.status === 'GATE_PASSED') && (
                    <Popconfirm
                      title="确认发布该版本？"
                      description="若绑定了门禁评测集，将先执行同语料双跑评测"
                      okText="发布"
                      cancelText="取消"
                      onConfirm={() => handleRelease(record.app_version_id)}
                    >
                      <Button size="small" type="primary" loading={acting}>
                        发布
                      </Button>
                    </Popconfirm>
                  )}
                  {record.status === 'GATE_LOG_ONLY' && (
                    <Button size="small" danger loading={acting} onClick={() => setForceModalVersion(record)}>
                      强制发布
                    </Button>
                  )}
                  {record.status === 'SUPERSEDED' && (
                    <Popconfirm
                      title="确认回滚到该历史版本？"
                      description="将以该版本固化的配置重新发布，原当前正式版将变为已下线"
                      okText="回滚"
                      cancelText="取消"
                      onConfirm={() => handleRollback(record.app_version_id)}
                    >
                      <Button size="small" loading={acting}>
                        回滚到此版本
                      </Button>
                    </Popconfirm>
                  )}
                  {hasCompareResult && (
                    <Button size="small" onClick={() => setCompareVersion(record)}>
                      查看双跑结果
                    </Button>
                  )}
                </Space>
              );
            },
          },
        ]}
        expandable={{
          expandedRowRender: (record) => {
            const kbRefs = resolveKbRefs(record.config);
            // M6-CONTRACTS.md section 0.10/2: index_snapshots is only populated for versions that
            // went through the M6 release path; absent/empty covers both "not yet RELEASED" and
            // "RELEASED before M6 shipped" -- both render the same empty state, never an error.
            const hasSnapshots = (record.index_snapshots?.length ?? 0) > 0;
            const snapshotsByKb = new Map<string, AppVersionIndexSnapshot[]>();
            (record.index_snapshots ?? []).forEach((snap) => {
              const list = snapshotsByKb.get(snap.kb_id) ?? [];
              list.push(snap);
              snapshotsByKb.set(snap.kb_id, list);
            });
            const visibleCountByKb = new Map<string, number>();
            (record.visible_version_kb_count ?? []).forEach((v) => visibleCountByKb.set(v.kb_id, v.version_count));

            return (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <Space wrap size={4}>
                  <Typography.Text type="secondary">关联知识库：</Typography.Text>
                  {kbRefs.map((ref) => (
                    <Tag key={ref.kb_id}>
                      {kbNameOf(kbs, ref.kb_id)} × {ref.weight}
                    </Tag>
                  ))}
                  <Tag color={record.config.routing?.enabled ? 'processing' : 'default'}>
                    知识库路由：{record.config.routing?.enabled ? '开启' : '关闭'}
                  </Tag>
                </Space>
                <Typography.Paragraph style={{ marginBottom: 0 }} type="secondary">
                  recall_top_k={record.config.retrieval.recall_top_k}；top_n=
                  {record.config.retrieval.top_n}；rerank_enabled={String(record.config.retrieval.rerank_enabled ?? false)}
                </Typography.Paragraph>
                <div>
                  <Typography.Text type="secondary">索引快照：</Typography.Text>
                  {hasSnapshots ? (
                    <Table<{ kb_id: string }>
                      size="small"
                      style={{ marginTop: 4 }}
                      pagination={false}
                      rowKey="kb_id"
                      dataSource={kbRefs.map((ref) => ({ kb_id: ref.kb_id }))}
                      columns={[
                        { title: '知识库', render: (_, row) => kbNameOf(kbs, row.kb_id) },
                        {
                          title: '物理索引名',
                          render: (_, row) => (
                            <Space direction="vertical" size={2}>
                              {(snapshotsByKb.get(row.kb_id) ?? []).map((snap) => (
                                <Tag key={snap.engine}>
                                  {snap.engine}: {snap.physical_index_name}
                                </Tag>
                              ))}
                            </Space>
                          ),
                        },
                        {
                          title: '可见集文档版本数',
                          width: 140,
                          render: (_, row) => visibleCountByKb.get(row.kb_id) ?? '-',
                        },
                      ]}
                    />
                  ) : (
                    <Alert
                      type="info"
                      showIcon
                      style={{ marginTop: 4 }}
                      message="无索引快照，调用走实时索引"
                      description="该版本未发布，或在 M6 索引快照能力上线前发布，检索按当前激活集合与 live 别名实时执行"
                    />
                  )}
                </div>
              </Space>
            );
          },
        }}
      />

      <Modal
        rootClassName="catalog-eval-modal"
        title="强制发布确认"
        open={forceModalVersion !== null}
        onCancel={() => setForceModalVersion(null)}
        onOk={() => forceModalVersion && handleRelease(forceModalVersion.app_version_id, true)}
        okText="确认强制发布"
        okButtonProps={{ danger: true }}
        cancelText="取消"
        destroyOnClose
      >
        {forceModalVersion && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Alert
              type="warning"
              showIcon
              message="该版本门禁结果为「仅记录不拦截」，未自动发布"
              description={
                forceModalVersion.gate_verdict ||
                '未绑定评测集 / 有效 case 不足 50 / 重试后仍含降级 case / 待复核占比超过 15% 中的一种或多种'
              }
            />
            <Typography.Text>强制发布将跳过门禁拦截直接生效，本次操作会留痕记录，请确认后再继续。</Typography.Text>
          </Space>
        )}
      </Modal>

      <GateCompareDrawer version={compareVersion} onClose={() => setCompareVersion(null)} />
    </div>
  );
}

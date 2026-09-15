import { useEffect, useState } from 'react';
import { Alert, Button, Drawer, Empty, Select, Spin, Tag, Typography } from 'antd';
import { listAppVersions } from '../../../api/app';
import type { AppVersion, KnowledgeBase } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
import { compareAppVersionConfig, formatConfigDifferenceValue } from './appVersionConfigDiff';
import AppVersionCorpusDiff from './AppVersionCorpusDiff';
import './AppVersionDiffDrawer.css';

interface Props { appId: string; versionId: string | null; kbs: KnowledgeBase[]; onClose: () => void }

/** 选择目标版本后重新读取保存快照；关闭或切换时销毁旧结果。 */
export default function AppVersionDiffDrawer(props: Props) {
  const { token, can } = useAuth();
  if (!props.versionId || !can(PERMISSIONS.APP_READ)) return null;
  return <VersionDiffContent key={`${token}:${props.appId}:${props.versionId}:${can(PERMISSIONS.KB_READ)}`} {...props} />;
}

function VersionDiffContent({ appId, versionId, kbs, onClose }: Props) {
  const { can } = useAuth();
  const [trigger] = useState(() => document.activeElement instanceof HTMLElement ? document.activeElement : null);
  const [versions, setVersions] = useState<AppVersion[]>([]);
  const [baselineId, setBaselineId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [readAt, setReadAt] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(false);
    setVersions([]);
    listAppVersions(appId).then(rows => {
      if (cancelled) return;
      if (!rows.some(row => row.app_version_id === versionId)) {
        setLoadError(true);
        return;
      }
      setVersions(rows);
      setBaselineId(rows.find(row => row.status === 'RELEASED' && row.app_version_id !== versionId)?.app_version_id
        ?? rows.find(row => row.app_version_id !== versionId)?.app_version_id ?? null);
      setReadAt(new Date().toLocaleTimeString());
    }).catch(() => { if (!cancelled) setLoadError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [appId, versionId, refresh]);

  const candidate = versions.find(row => row.app_version_id === versionId);
  const baseline = versions.find(row => row.app_version_id === baselineId) ?? null;
  const changes = candidate ? compareAppVersionConfig(baseline?.config ?? null, candidate.config) : [];
  if (candidate && (baseline?.gate_dataset_id ?? null) !== (candidate.gate_dataset_id ?? null)) {
    changes.push({ path: 'gate_dataset_id', label: '门禁评测集', before: baseline?.gate_dataset_id, after: candidate.gate_dataset_id });
  }
  const retry = () => { setVersions([]); setLoading(true); setRefresh(value => value + 1); };
  const close = () => {
    onClose();
    // 内容按版本销毁，关闭动画不会保留抽屉实例；在 DOM 移除后恢复原操作位置。
    queueMicrotask(() => { if (trigger?.isConnected) trigger.focus({ preventScroll: true }); });
  };

  return <Drawer rootClassName="catalog-eval-drawer" title="版本差异" open onClose={close} width={960}
    extra={<Button onClick={retry} loading={loading}>刷新差异</Button>}>
    <div className="app-version-diff">
      {loading ? <div className="app-version-diff__loading" role="status"><Spin /> 正在读取版本快照…</div> : loadError || !candidate ? (
        <Alert type="error" showIcon message="版本差异加载失败" description="版本可能已被移除或当前无权读取，请刷新重试。"
          action={<Button onClick={retry}>重试</Button>} />
      ) : <>
        <div className="app-version-diff__selection">
          <label>对照版本
            <Select aria-label="对照版本" value={baselineId ?? ''} onChange={value => setBaselineId(value || null)}
              options={[{ value: '', label: '无对照（空基线）' }, ...versions.filter(row => row.app_version_id !== versionId)
                .map(row => ({ value: row.app_version_id, label: `${row.version}${row.status === 'RELEASED' ? ' · 当前正式版' : ''}` }))]} />
          </label>
          <div><span>目标版本</span><strong>{candidate.version}</strong>
            {candidate.status === 'DRAFT' && <Tag>草稿</Tag>}</div>
        </div>
        <Typography.Paragraph type="secondary" className="app-version-diff__note">
          {readAt} 读取保存的配置。草稿尚未固化的参数在提交测试时补全；未设置值不代表最终运行值。
        </Typography.Paragraph>
        <section aria-label="配置差异">
          <h2 className="app-version-diff__section-title">配置差异 <span className="app-version-diff__count">{changes.length} 项</span></h2>
          {changes.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="保存的配置无差异" /> : changes.map(change => (
            <article key={change.path} className="app-version-diff__change">
              <h3>{change.label}</h3>
              <div className="app-version-diff__values">
                <div><span>对照 · {baseline?.version ?? '无对照'}</span><div className="app-version-diff__value">{formatConfigDifferenceValue(change.before)}</div></div>
                <div className="app-version-diff__target"><span>目标 · {candidate.version}</span><div className="app-version-diff__value">{formatConfigDifferenceValue(change.after)}</div></div>
              </div>
            </article>
          ))}
        </section>
        {can(PERMISSIONS.KB_READ) ? (
          <AppVersionCorpusDiff key={`${candidate.app_version_id}:${baselineId}:${refresh}`} candidateId={candidate.app_version_id}
            baselineId={baselineId} kbs={kbs} />
        ) : <Alert type="info" showIcon message="资料差异需要知识库读取权限" />}
      </>}
    </div>
  </Drawer>;
}

import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Pagination, Spin, Tag, Typography } from 'antd';
import { compareAppVersionCorpus, type AppCorpusComparison } from '../../../api/appVersionComparison';
import type { KnowledgeBase } from '../../../api/types';
import { kbNameOf } from '../../../utils/kbRefs';

interface Props { candidateId: string; baselineId: string | null; kbs: KnowledgeBase[] }
const SOURCE_LABELS = { EMPTY: '空基线', FROZEN: '发布时冻结资料', CURRENT: '当前资料（未冻结）', UNAVAILABLE: '历史快照不可用' };
const CHANGE_LABELS = { ADDED: '新增', REMOVED: '移除', UPDATED: '版本替换' };

/** 当前权限先裁剪再比较；读取失败或历史缺失都不能呈现为“无差异”。 */
export default function AppVersionCorpusDiff({ candidateId, baselineId, kbs }: Props) {
  const [page, setPage] = useState(1);
  const [refresh, setRefresh] = useState(0);
  const [result, setResult] = useState<AppCorpusComparison | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setResult(null);
    setLoadError(false);
    compareAppVersionCorpus(candidateId, baselineId, page).then(data => {
      if (!cancelled) setResult(data);
    }).catch(() => { if (!cancelled) setLoadError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [candidateId, baselineId, page, refresh]);

  return <section className="app-version-diff__corpus" aria-label="资料差异">
    <h2 className="app-version-diff__section-title">资料差异</h2>
    <Typography.Paragraph type="secondary">
      仅比较当前权限允许读取的资料，名称使用当前文档名称。发布冻结资料版本，权限仍按当前授权判断。
    </Typography.Paragraph>
    {loading ? <div role="status"><Spin /> 正在比较资料…</div> : loadError || !result ? (
      <Alert type="error" showIcon message="资料差异读取失败" description="请重试，或检查当前应用与知识库权限。配置差异可继续查看。"
        action={<Button onClick={() => { setResult(null); setLoading(true); setRefresh(value => value + 1); }}>重试资料差异</Button>} />
    ) : <>
      <div className="app-version-diff__values app-version-diff__corpus-sources">
        {(['baseline', 'candidate'] as const).map(side => <div key={side}>
          <span>{side === 'baseline' ? '对照资料' : '目标资料'}</span>
          <strong>{SOURCE_LABELS[result[side].source]}</strong>
          {result[side].complete && result[side].document_count != null && <span>{result[side].document_count} 份可读取资料</span>}
        </div>)}
      </div>
      {!result.comparable ? <Alert type="warning" showIcon message="无法完整比较资料"
        description="历史冻结集合或其中的文档版本已缺失。无法据此判断资料无差异；配置比较仍然有效。" /> : <>
        <p className="app-version-diff__totals">新增 {result.added ?? 0} · 移除 {result.removed ?? 0} · 版本替换 {result.updated ?? 0}</p>
        {result.total === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前可读取资料无差异" /> : result.items.map(item => (
          <article className="app-version-diff__document" key={`${item.kb_id}:${item.doc_id}`}>
            <div><Tag>{CHANGE_LABELS[item.change]}</Tag><strong>{item.file_name}</strong></div>
            <span>{kbNameOf(kbs, item.kb_id)}</span>
            <div className="app-version-diff__values"><div><span>对照版本</span><strong>{item.baseline?.version ?? '不在对照资料中'}</strong></div>
              <div><span>目标版本</span><strong>{item.candidate?.version ?? '不在目标资料中'}</strong></div></div>
          </article>
        ))}
        {(result.total ?? 0) > result.page_size && <Pagination current={page} pageSize={result.page_size} total={result.total ?? 0}
          showSizeChanger={false} onChange={value => { setResult(null); setLoading(true); setPage(value); }} />}
      </>}
    </>}
  </section>;
}

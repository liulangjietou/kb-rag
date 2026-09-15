import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Drawer, Space, Table, Tag, Typography } from 'antd';
import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
import { listExtSourceItems, retryFailedExtSource } from '../../../api/extSource';
import type { ExtSource, ExtSourceItem } from '../../../api/types';
import { EXT_SOURCE_ITEM_STATUS_META, metaOf } from '../../../utils/statusMeta';
import './ExtSourceItemsDrawer.css';

const PAGE_SIZE = 20;

interface Props {
  source: ExtSource | null;
  onClose: () => void;
}

/** 对象明细按来源和登录会话隔离，切换后旧请求不能覆盖新来源。 */
export default function ExtSourceItemsDrawer({ source, onClose }: Props) {
  const { token } = useAuth();
  return <Drawer title={source ? `同步明细 · ${source.name}` : '同步明细'} width={860}
    open={Boolean(source)} onClose={onClose} destroyOnHidden>
    {source && <ItemResults key={`${source.source_id}:${token}`} source={source} />}
  </Drawer>;
}

function ItemResults({ source }: { source: ExtSource }) {
  const { can } = useAuth();
  const [items, setItems] = useState<ExtSourceItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [failedOnly, setFailedOnly] = useState(true);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [retryState, setRetryState] = useState<'idle' | 'accepted' | 'unknown'>('idle');
  const sequence = useRef(0);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; sequence.current += 1; };
  }, []);

  const load = useCallback(async (targetPage: number) => {
    const current = ++sequence.current;
    setLoading(true); setLoadError(false);
    try {
      const result = await listExtSourceItems(source.source_id, targetPage, PAGE_SIZE, failedOnly);
      if (!active.current || sequence.current !== current) return;
      setItems(result.items); setTotal(result.total); setPage(targetPage);
    } catch {
      if (active.current && sequence.current === current) { setItems([]); setLoadError(true); }
    } finally {
      if (active.current && sequence.current === current) setLoading(false);
    }
  }, [source.source_id, failedOnly]);

  useEffect(() => { void load(1); return () => { sequence.current += 1; }; }, [load]);

  const retry = async () => {
    setSubmitting(true); setRetryState('idle');
    try {
      await retryFailedExtSource(source.source_id);
      if (active.current) setRetryState('accepted');
    } catch {
      if (active.current) setRetryState('unknown');
    } finally {
      if (active.current) setSubmitting(false);
    }
  };

  return <section className="ext-source-results" aria-label="对象同步结果">
    <Space wrap>
      <Checkbox checked={failedOnly} onChange={event => setFailedOnly(event.target.checked)}>仅看失败对象</Checkbox>
      <Button onClick={() => void load(page)} loading={loading}>刷新明细</Button>
      {can(PERMISSIONS.DOC_WRITE) && <Button type="primary" onClick={() => void retry()}
        loading={submitting} disabled={loading || loadError || total === 0}>仅重试失败项</Button>}
    </Space>
    <Typography.Paragraph type="secondary">
      重试只处理已失败对象，不接入新对象。完成后刷新明细查看逐项结果；完整来源状态由“立即同步”更新。
    </Typography.Paragraph>
    {retryState === 'accepted' && <Alert type="info" showIcon message="失败项重试已提交"
      description="后台按扫描上限处理。请刷新明细确认结果；关闭页面不会取消已提交的重试。" />}
    {retryState === 'unknown' && <Alert type="warning" showIcon message="未确认重试是否受理"
      description="请先刷新明细核对对象状态，避免连续重复提交。" />}
    {loadError ? <Alert type="error" showIcon message="对象明细加载失败" description="当前无法确认对象状态，请刷新明细重试。" />
      : <Table<ExtSourceItem> rowKey="object_key" loading={loading} dataSource={items}
        onRow={() => ({ tabIndex: 0 })}
        scroll={{ x: 680 }} pagination={{ current: page, pageSize: PAGE_SIZE, total, showSizeChanger: false,
          showTotal: value => `共 ${value} 个${failedOnly ? '失败' : ''}对象`, onChange: value => void load(value) }}
        columns={[
          { title: source.source_type === 'confluence' ? '页面 Key' : '对象 Key', dataIndex: 'object_key', width: 270,
            render: (key: string, item) => <div className="ext-source-results__outcome">
              <span className="ext-source-results__key">{key}</span>
              {item.last_error && <span className="ext-source-results__reason">{item.last_error}</span>}
            </div> },
          { title: '状态', width: 100, render: (_, item) => {
            const meta = item.last_status && metaOf(EXT_SOURCE_ITEM_STATUS_META, item.last_status);
            return <Tag color={meta ? meta.color : undefined}>{meta ? meta.label : '未同步'}</Tag>;
          } },
          { title: '最近处理时间', dataIndex: 'last_sync_at', width: 180,
            render: (value: string | null) => value ? <time dateTime={value}>{value.replace('T', ' ')}</time> : '暂无记录' },
        ]} />}
  </section>;
}

import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Button, Form, Input, Popconfirm, Space, Switch, Table, Tag, Tooltip, Typography, message } from 'antd';
import {
  listWebSources,
  registerWebSource,
  removeWebSource,
  syncWebSource,
  updateWebSource,
} from '../../../api/webSource';
import type { WebSourceEntry, WebSourceFetchStatus } from '../../../api/types';
import { WEB_SOURCE_STATUS_META, metaOf } from '../../../utils/statusMeta';

interface WebSourcesTabProps {
  kbId: string;
  /** Fired after a sync that may have created/updated a document, so the parent refreshes the list. */
  onSynced: () => void;
}

const PAGE_SIZE = 20;

/** Register + manual sync both write a fetch outcome onto the row; turn it into operator feedback. */
function reportOutcome(entry: WebSourceEntry) {
  switch (entry.last_fetch_status) {
    case 'SUCCESS':
      message.success(`已抓取并入库：${entry.file_name ?? entry.url}`);
      break;
    case 'UNCHANGED':
      message.info('页面内容未变化，未生成新版本');
      break;
    case 'SKIPPED':
      message.warning(entry.last_error ?? '绑定的文档在回收站中，本次同步已跳过');
      break;
    case 'FAILED':
      message.error(`抓取失败：${entry.last_error ?? '未知原因'}`);
      break;
    default:
      break;
  }
}

/**
 * 网页导入 tab of the KB detail page (M12-CONTRACTS.md section 3.4): register page URLs, watch
 * each one's last sync outcome, trigger a sync by hand, flip the nightly-sync switch, remove the
 * registration. The fetch outcome never surfaces as a request error -- register and manual sync
 * always resolve and carry the outcome on the row, which is why every action re-reads the list.
 */
export default function WebSourcesTab({ kbId, onSynced }: WebSourcesTabProps) {
  const { can } = useAuth();
  const canWrite = can(PERMISSIONS.DOC_WRITE);
  const [items, setItems] = useState<WebSourceEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [registering, setRegistering] = useState(false);
  // source_id of the row whose sync/toggle/remove request is in flight, to scope the spinners.
  const [actingId, setActingId] = useState<string | null>(null);
  const [form] = Form.useForm<{ url: string; render_js?: boolean }>();

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    try {
      const result = await listWebSources(kbId, targetPage);
      setItems(result.items);
      setTotal(result.total);
      setPage(targetPage);
    } finally {
      setLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    load(1);
  }, [load]);

  const handleRegister = async (values: { url: string; render_js?: boolean }) => {
    setRegistering(true);
    try {
      const entry = await registerWebSource(kbId, {
        url: values.url.trim(),
        render_js: values.render_js ?? false,
      });
      reportOutcome(entry);
      form.resetFields();
      load(1);
      if (entry.last_fetch_status === 'SUCCESS') {
        onSynced();
      }
    } finally {
      setRegistering(false);
    }
  };

  const handleSync = async (row: WebSourceEntry) => {
    setActingId(row.source_id);
    try {
      const entry = await syncWebSource(row.source_id);
      reportOutcome(entry);
      load(page);
      if (entry.last_fetch_status === 'SUCCESS') {
        onSynced();
      }
    } finally {
      setActingId(null);
    }
  };

  const handleToggle = async (row: WebSourceEntry, enabled: boolean) => {
    setActingId(row.source_id);
    try {
      await updateWebSource(row.source_id, { sync_enabled: enabled });
      message.success(enabled ? '已开启定时同步' : '已关闭定时同步');
      load(page);
    } finally {
      setActingId(null);
    }
  };

  const handleToggleRender = async (row: WebSourceEntry, enabled: boolean) => {
    setActingId(row.source_id);
    try {
      await updateWebSource(row.source_id, { render_js: enabled });
      message.success(enabled ? '已开启 JS 渲染抓取' : '已关闭 JS 渲染抓取');
      load(page);
    } finally {
      setActingId(null);
    }
  };

  const handleRemove = async (row: WebSourceEntry) => {
    setActingId(row.source_id);
    try {
      await removeWebSource(row.source_id);
      message.success('已移除登记，已入库的文档保持不变');
      load(page);
    } finally {
      setActingId(null);
    }
  };

  return (
    <>
      <Typography.Paragraph type="secondary">
        登记网页地址后立即抓取入库；开启定时同步的地址会在每日定时任务中重新抓取，内容有变化时生成新版本。
        移除登记不会删除已入库的文档。
      </Typography.Paragraph>

      <Form hidden={!canWrite} form={form} layout="inline" onFinish={handleRegister} style={{ marginBottom: 16 }}>
        <Form.Item
          name="url"
          style={{ flex: 1, maxWidth: 560 }}
          rules={[
            { required: true, message: '请输入网页地址' },
            { type: 'url', message: '请输入合法的 http/https 地址' },
          ]}
        >
          <Input placeholder="https://example.com/docs/guide" allowClear />
        </Form.Item>
        <Form.Item
          name="render_js"
          valuePropName="checked"
          label="JS 渲染"
          tooltip="开启后走无头浏览器渲染 JS 再入库，适用于正文由脚本生成的页面；抓取更慢"
        >
          {/* The Switch must be the direct child: Form.Item injects checked/onChange into it,
              and wrapping it in a Space would leave the value out of the form. */}
          <Switch size="small" />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={registering}>
            登记并抓取
          </Button>
        </Form.Item>
      </Form>

      <Table<WebSourceEntry>
        rowKey="source_id"
        loading={loading}
        dataSource={items}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (nextPage) => load(nextPage),
        }}
        columns={[
          {
            title: '网页地址',
            dataIndex: 'url',
            ellipsis: { showTitle: false },
            render: (url: string) => (
              <Tooltip title={url} placement="topLeft">
                <a href={url} target="_blank" rel="noreferrer">
                  {url}
                </a>
              </Tooltip>
            ),
          },
          {
            title: '绑定文档',
            dataIndex: 'file_name',
            width: 220,
            ellipsis: { showTitle: false },
            render: (name: string | null) =>
              name ? (
                <Tooltip title={name} placement="topLeft">
                  {name}
                </Tooltip>
              ) : (
                <Typography.Text type="secondary">未入库</Typography.Text>
              ),
          },
          {
            title: '定时同步',
            dataIndex: 'sync_enabled',
            width: 100,
            render: (_, record) => (
              <Switch
                disabled={!canWrite}
                size="small"
                checked={record.sync_enabled}
                loading={actingId === record.source_id}
                onChange={(checked) => handleToggle(record, checked)}
              />
            ),
          },
          {
            title: 'JS 渲染',
            dataIndex: 'render_js',
            width: 100,
            render: (_, record) => (
              <Switch
                disabled={!canWrite}
                size="small"
                checked={record.render_js}
                loading={actingId === record.source_id}
                onChange={(checked) => handleToggleRender(record, checked)}
              />
            ),
          },
          {
            title: '最近状态',
            dataIndex: 'last_fetch_status',
            width: 110,
            render: (status: WebSourceFetchStatus | null, record) => {
              if (!status) {
                return <Tag>未抓取</Tag>;
              }
              const meta = metaOf(WEB_SOURCE_STATUS_META, status);
              const tag = <Tag color={meta.color}>{meta.label}</Tag>;
              // FAILED/SKIPPED keep the reason on the row; surface it without widening the table.
              return record.last_error ? <Tooltip title={record.last_error}>{tag}</Tooltip> : tag;
            },
          },
          { title: '最近抓取时间', dataIndex: 'last_fetch_at', width: 180 },
          {
            title: '操作',
            width: 180,
            render: (_, record) => canWrite ? (
              <Space>
                <Button
                  size="small"
                  type="link"
                  loading={actingId === record.source_id}
                  onClick={() => handleSync(record)}
                >
                  立即同步
                </Button>
                <Popconfirm
                  title="移除该登记？"
                  description="仅移除网页登记，已抓取入库的文档会保留在知识库中。"
                  okText="移除"
                  okButtonProps={{ danger: true }}
                  cancelText="取消"
                  onConfirm={() => handleRemove(record)}
                >
                  <Button size="small" type="link" danger loading={actingId === record.source_id}>
                    移除登记
                  </Button>
                </Popconfirm>
              </Space>
            ) : null,
          },
        ]}
      />
    </>
  );
}

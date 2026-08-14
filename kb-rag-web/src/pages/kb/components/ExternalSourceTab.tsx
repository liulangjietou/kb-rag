// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  listExtSourceItems,
  listExtSources,
  registerExtSource,
  removeExtSource,
  syncExtSource,
  testExtSource,
  updateExtSource,
} from '../../../api/extSource';
import type {
  ExtSource,
  ExtSourceItem,
  ExtSourceItemStatus,
  ExtSourceSyncStatus,
  RegisterExtSourceRequest,
} from '../../../api/types';
import {
  EXT_SOURCE_ITEM_STATUS_META,
  EXT_SOURCE_SYNC_STATUS_META,
  metaOf,
} from '../../../utils/statusMeta';

interface ExternalSourceTabProps {
  kbId: string;
  /** Fired after a sync that may have created/updated documents, so the parent refreshes the list. */
  onSynced: () => void;
}

const PAGE_SIZE = 20;

type SourceType = 's3' | 'confluence';

const SOURCE_TYPE_S3: SourceType = 's3';
const SOURCE_TYPE_CONFLUENCE: SourceType = 'confluence';

const CONNECTOR_META: Record<SourceType, {
  label: string;
  endpointLabel: string;
  endpointPlaceholder: string;
  collectionLabel: string;
  collectionPlaceholder: string;
  accessLabel: string;
  accessPlaceholder: string;
  secretLabel: string;
  secretPlaceholder: string;
}> = {
  s3: {
    label: 'S3 / OSS 兼容对象存储',
    endpointLabel: 'Endpoint',
    endpointPlaceholder: 'https://oss-cn-hangzhou.aliyuncs.com',
    collectionLabel: 'Bucket',
    collectionPlaceholder: 'my-docs-bucket',
    accessLabel: 'Access Key',
    accessPlaceholder: 'Access Key ID',
    secretLabel: 'Secret Key',
    secretPlaceholder: 'Secret Access Key',
  },
  confluence: {
    label: 'Confluence Cloud',
    endpointLabel: 'Site URL',
    endpointPlaceholder: 'https://your-domain.atlassian.net',
    collectionLabel: 'Space Key',
    collectionPlaceholder: '例如：ENG',
    accessLabel: 'Atlassian 账号邮箱',
    accessPlaceholder: 'reader@example.com',
    secretLabel: 'API Token',
    secretPlaceholder: 'Atlassian API Token',
  },
};

/** Shape the register/edit form binds to; secret_key is optional on edit (blank keeps the stored one). */
interface SourceFormValues {
  source_type: SourceType;
  name: string;
  endpoint: string;
  region?: string;
  bucket: string;
  prefix?: string;
  access_key: string;
  secret_key?: string;
  sync_enabled: boolean;
}

/**
 * 外部数据源 tab of the KB detail page (M14/M23): register an S3/OSS bucket or a Confluence Cloud
 * space, watch its per-object/page outcome, trigger a scan, test, edit and remove. A scan runs off
 * the request thread, so sync only acknowledges acceptance and the list is re-read for its outcome.
 */
export default function ExternalSourceTab({ kbId, onSynced }: ExternalSourceTabProps) {
  const [items, setItems] = useState<ExtSource[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  // source_id of the row whose sync/test/toggle/remove request is in flight, to scope the spinners.
  const [actingId, setActingId] = useState<string | null>(null);
  // null while the modal is closed; the row being edited, or a sentinel for a fresh registration.
  const [editing, setEditing] = useState<ExtSource | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  // The source whose per-object item rows the drawer is showing, null while it is closed.
  const [itemsSource, setItemsSource] = useState<ExtSource | null>(null);
  const [form] = Form.useForm<SourceFormValues>();
  const selectedType = Form.useWatch('source_type', form) ?? SOURCE_TYPE_S3;
  const connectorMeta = CONNECTOR_META[selectedType];

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    try {
      const result = await listExtSources(kbId, targetPage);
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

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ source_type: SOURCE_TYPE_S3, sync_enabled: true });
    setModalOpen(true);
  };

  const openEdit = (row: ExtSource) => {
    setEditing(row);
    form.setFieldsValue({
      source_type: sourceTypeOf(row.source_type),
      name: row.name,
      endpoint: row.endpoint,
      region: row.region ?? undefined,
      bucket: row.bucket,
      prefix: row.prefix ?? undefined,
      access_key: row.access_key,
      // The read API always masks the secret, so an edit form has nothing real to echo back.
      secret_key: undefined,
      sync_enabled: row.sync_enabled,
    });
    setModalOpen(true);
  };

  const handleSubmit = async (values: SourceFormValues) => {
    setSaving(true);
    try {
      const sourceType = editing ? sourceTypeOf(editing.source_type) : values.source_type;
      const isConfluence = sourceType === SOURCE_TYPE_CONFLUENCE;
      if (editing) {
        await updateExtSource(editing.source_id, {
          name: values.name.trim(),
          endpoint: values.endpoint.trim(),
          region: isConfluence ? undefined : values.region?.trim() || undefined,
          bucket: values.bucket.trim(),
          prefix: isConfluence ? undefined : values.prefix?.trim() || undefined,
          access_key: values.access_key.trim(),
          // Blank keeps the stored secret; only send a new one when the operator typed it.
          secret_key: values.secret_key?.trim() || undefined,
          sync_enabled: values.sync_enabled,
        });
        message.success('已更新数据源');
      } else {
        const payload: RegisterExtSourceRequest = {
          source_type: sourceType,
          name: values.name.trim(),
          endpoint: values.endpoint.trim(),
          region: isConfluence ? undefined : values.region?.trim() || undefined,
          bucket: values.bucket.trim(),
          prefix: isConfluence ? undefined : values.prefix?.trim() || undefined,
          access_key: values.access_key.trim(),
          secret_key: values.secret_key!.trim(),
          sync_enabled: values.sync_enabled,
        };
        await registerExtSource(kbId, payload);
        message.success('已登记数据源，首次扫描已在后台开始');
      }
      setModalOpen(false);
      load(editing ? page : 1);
    } finally {
      setSaving(false);
    }
  };

  const handleSync = async (row: ExtSource) => {
    setActingId(row.source_id);
    try {
      await syncExtSource(row.source_id);
      message.info('已提交同步，扫描在后台进行，稍后刷新查看结果');
      // The outcome lands on the rows later; nudge the parent so newly ingested docs surface too.
      onSynced();
      load(page);
    } finally {
      setActingId(null);
    }
  };

  const handleTest = async (row: ExtSource) => {
    setActingId(row.source_id);
    try {
      const result = await testExtSource(row.source_id);
      if (result.up) {
        message.success(`连接正常：${result.detail}`);
      } else {
        message.error(`连接失败：${result.detail}`);
      }
    } finally {
      setActingId(null);
    }
  };

  const handleToggle = async (row: ExtSource, enabled: boolean) => {
    setActingId(row.source_id);
    try {
      // The update endpoint keeps the stored secret on a blank one, so a bare toggle is safe.
      await updateExtSource(row.source_id, {
        name: row.name,
        endpoint: row.endpoint,
        region: row.region ?? undefined,
        bucket: row.bucket,
        prefix: row.prefix ?? undefined,
        access_key: row.access_key,
        sync_enabled: enabled,
      });
      message.success(enabled ? '已开启定时同步' : '已关闭定时同步');
      load(page);
    } finally {
      setActingId(null);
    }
  };

  const handleRemove = async (row: ExtSource) => {
    setActingId(row.source_id);
    try {
      await removeExtSource(row.source_id);
      message.success('已移除数据源，已入库的文档保持不变');
      load(page);
    } finally {
      setActingId(null);
    }
  };

  return (
    <>
      <Typography.Paragraph type="secondary">
        支持登记 S3/OSS 兼容对象存储或 Confluence Cloud 空间；系统按对象 ETag / 页面版本增量同步并复用普通文档入库链路。
        开启定时同步后会按计划重新扫描，移除数据源不会删除已入库的文档。
      </Typography.Paragraph>

      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" onClick={openCreate}>
          登记数据源
        </Button>
        <Button onClick={() => load(page)}>刷新</Button>
      </Space>

      <Table<ExtSource>
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
            title: '类型',
            dataIndex: 'source_type',
            width: 130,
            render: (type: string) => CONNECTOR_META[sourceTypeOf(type)].label,
          },
          {
            title: '名称',
            dataIndex: 'name',
            width: 160,
            ellipsis: { showTitle: false },
            render: (name: string) => (
              <Tooltip title={name} placement="topLeft">
                {name}
              </Tooltip>
            ),
          },
          {
            title: '同步范围',
            width: 220,
            ellipsis: { showTitle: false },
            render: (_, record) => {
              const isConfluence = sourceTypeOf(record.source_type) === SOURCE_TYPE_CONFLUENCE;
              const label = isConfluence
                ? `Space · ${record.bucket}`
                : record.prefix ? `${record.bucket}/${record.prefix}` : record.bucket;
              return (
                <Tooltip title={`${record.endpoint} · ${label}`} placement="topLeft">
                  {label}
                </Tooltip>
              );
            },
          },
          {
            title: '定时同步',
            dataIndex: 'sync_enabled',
            width: 100,
            render: (_, record) => (
              <Switch
                size="small"
                checked={record.sync_enabled}
                loading={actingId === record.source_id}
                onChange={(checked) => handleToggle(record, checked)}
              />
            ),
          },
          {
            title: '最近同步',
            dataIndex: 'last_sync_status',
            width: 110,
            render: (status: ExtSourceSyncStatus | null, record) => {
              if (!status) {
                return <Tag>未同步</Tag>;
              }
              const meta = metaOf(EXT_SOURCE_SYNC_STATUS_META, status);
              const tag = <Tag color={meta.color}>{meta.label}</Tag>;
              // PARTIAL/FAILED keep the reason on the row; surface it without widening the table.
              return record.last_error ? <Tooltip title={record.last_error}>{tag}</Tooltip> : tag;
            },
          },
          { title: '最近同步时间', dataIndex: 'last_sync_at', width: 180 },
          {
            title: '操作',
            width: 300,
            render: (_, record) => (
              <Space size={0} wrap>
                <Button
                  size="small"
                  type="link"
                  loading={actingId === record.source_id}
                  onClick={() => handleSync(record)}
                >
                  立即同步
                </Button>
                <Button
                  size="small"
                  type="link"
                  loading={actingId === record.source_id}
                  onClick={() => handleTest(record)}
                >
                  测试连接
                </Button>
                <Button size="small" type="link" onClick={() => setItemsSource(record)}>
                  查看对象
                </Button>
                <Button size="small" type="link" onClick={() => openEdit(record)}>
                  编辑
                </Button>
                <Popconfirm
                  title="移除该数据源？"
                  description="仅移除数据源登记，已扫描入库的文档会保留在知识库中。"
                  okText="移除"
                  okButtonProps={{ danger: true }}
                  cancelText="取消"
                  onConfirm={() => handleRemove(record)}
                >
                  <Button size="small" type="link" danger loading={actingId === record.source_id}>
                    移除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      {/* Keep Form mounted: openEdit fills it before opening; unmounted rc-field-form drops assignments. */}
      <Modal
        title={editing ? '编辑数据源' : '登记数据源'}
        open={modalOpen}
        confirmLoading={saving}
        onOk={() => form.submit()}
        onCancel={() => setModalOpen(false)}
        okText={editing ? '保存' : '登记'}
        cancelText="取消"
        forceRender
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit} preserve={false}>
          <Form.Item name="source_type" label="连接器类型" rules={[{ required: true, message: '请选择连接器类型' }]}>
            <Select
              disabled={Boolean(editing)}
              options={[
                { value: SOURCE_TYPE_S3, label: CONNECTOR_META.s3.label },
                { value: SOURCE_TYPE_CONFLUENCE, label: CONNECTOR_META.confluence.label },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="name"
            label="名称"
            rules={[{ required: true, message: '请输入名称' }, { max: 128, message: '最多 128 个字符' }]}
          >
            <Input placeholder={selectedType === SOURCE_TYPE_CONFLUENCE ? '例如：研发知识空间' : '例如：产品手册归档桶'} allowClear />
          </Form.Item>
          <Form.Item
            name="endpoint"
            label={connectorMeta.endpointLabel}
            rules={[{ required: true, message: `请输入 ${connectorMeta.endpointLabel}` }, { max: 512, message: '最多 512 个字符' }]}
          >
            <Input placeholder={connectorMeta.endpointPlaceholder} allowClear />
          </Form.Item>
          {selectedType === SOURCE_TYPE_S3 && (
            <Form.Item name="region" label="Region（可选）" rules={[{ max: 64, message: '最多 64 个字符' }]}>
              <Input placeholder="cn-hangzhou" allowClear />
            </Form.Item>
          )}
          <Form.Item
            name="bucket"
            label={connectorMeta.collectionLabel}
            rules={[{ required: true, message: `请输入 ${connectorMeta.collectionLabel}` }, { max: 128, message: '最多 128 个字符' }]}
          >
            <Input placeholder={connectorMeta.collectionPlaceholder} allowClear />
          </Form.Item>
          {selectedType === SOURCE_TYPE_S3 && (
            <Form.Item name="prefix" label="前缀（可选）" rules={[{ max: 512, message: '最多 512 个字符' }]}>
              <Input placeholder="docs/manuals/" allowClear />
            </Form.Item>
          )}
          <Form.Item
            name="access_key"
            label={connectorMeta.accessLabel}
            rules={[{ required: true, message: `请输入 ${connectorMeta.accessLabel}` }, { max: 256, message: '最多 256 个字符' }]}
          >
            <Input placeholder={connectorMeta.accessPlaceholder} allowClear />
          </Form.Item>
          <Form.Item
            name="secret_key"
            label={editing ? `${connectorMeta.secretLabel}（留空保留原密钥）` : connectorMeta.secretLabel}
            rules={
              editing
                ? [{ max: 512, message: '最多 512 个字符' }]
                : [{ required: true, message: `请输入 ${connectorMeta.secretLabel}` }, { max: 512, message: '最多 512 个字符' }]
            }
          >
            <Input.Password placeholder={editing ? '不修改请留空' : connectorMeta.secretPlaceholder} allowClear />
          </Form.Item>
          <Form.Item name="sync_enabled" label="定时同步" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <ExtSourceItemsDrawer source={itemsSource} onClose={() => setItemsSource(null)} />
    </>
  );
}

interface ExtSourceItemsDrawerProps {
  /** The source whose object rows to show; null keeps the drawer closed. */
  source: ExtSource | null;
  onClose: () => void;
}

/** Per-object/page sync outcome drawer of one external source (M14/M23). */
function ExtSourceItemsDrawer({ source, onClose }: ExtSourceItemsDrawerProps) {
  const [items, setItems] = useState<ExtSourceItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async (sourceId: string, targetPage: number) => {
    setLoading(true);
    try {
      const result = await listExtSourceItems(sourceId, targetPage);
      setItems(result.items);
      setTotal(result.total);
      setPage(targetPage);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (source) {
      load(source.source_id, 1);
    }
  }, [source, load]);

  return (
    <Drawer
      title={source ? `同步明细 · ${source.name}` : '同步明细'}
      width={720}
      open={Boolean(source)}
      onClose={onClose}
      destroyOnClose
    >
      <Table<ExtSourceItem>
        rowKey="object_key"
        loading={loading}
        dataSource={items}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (nextPage) => source && load(source.source_id, nextPage),
        }}
        columns={[
          {
            title: source && sourceTypeOf(source.source_type) === SOURCE_TYPE_CONFLUENCE ? '页面 Key' : '对象 Key',
            dataIndex: 'object_key',
            ellipsis: { showTitle: false },
            render: (key: string) => (
              <Tooltip title={key} placement="topLeft">
                {key}
              </Tooltip>
            ),
          },
          {
            title: '状态',
            dataIndex: 'last_status',
            width: 100,
            render: (status: ExtSourceItemStatus | null, record) => {
              if (!status) {
                return <Tag>未同步</Tag>;
              }
              const meta = metaOf(EXT_SOURCE_ITEM_STATUS_META, status);
              const tag = <Tag color={meta.color}>{meta.label}</Tag>;
              return record.last_error ? <Tooltip title={record.last_error}>{tag}</Tooltip> : tag;
            },
          },
          { title: '最近同步时间', dataIndex: 'last_sync_at', width: 180 },
        ]}
      />
    </Drawer>
  );
}

/** Unknown future connector values stay visible with the conservative S3-shaped fallback. */
function sourceTypeOf(value: string): SourceType {
  return value === SOURCE_TYPE_CONFLUENCE ? SOURCE_TYPE_CONFLUENCE : SOURCE_TYPE_S3;
}

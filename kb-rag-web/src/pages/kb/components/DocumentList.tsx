import type { ReactNode } from 'react';
import { Checkbox, Empty, Grid, Pagination, Space, Spin, Table, Tag, Tooltip } from 'antd';
import { PUBLISH_STATUS_META, type KbDocument } from '../../../api/types';
import { formatFileSize } from '../../../utils/format';
import { metaOf, PROCESS_STATUS_META } from '../../../utils/statusMeta';

interface DocumentListProps {
  documents: KbDocument[];
  loading: boolean;
  page: number;
  pageSize: number;
  total: number;
  selectedIds: string[];
  canSelect: boolean;
  onSelect: (ids: string[]) => void;
  onPageChange: (page: number, size: number) => void;
  actions: (doc: KbDocument) => ReactNode;
}

function ProcessingState({ doc }: { doc: KbDocument }) {
  const meta = metaOf(PROCESS_STATUS_META, doc.process_status);
  return (
    <Tooltip title={doc.fail_reason}>
      <Tag color={meta.color}>{meta.label}</Tag>
    </Tooltip>
  );
}

function PublishingState({ doc }: { doc: KbDocument }) {
  const meta = metaOf(PUBLISH_STATUS_META, doc.publish_status);
  return (
    <Tooltip title={doc.publish_status === 'REJECTED' ? doc.review_note : null}>
      <Tag color={meta.color}>{meta.label}</Tag>
    </Tooltip>
  );
}

function ValidityState({ doc }: { doc: KbDocument }) {
  const expired = doc.expires_at && new Date(doc.expires_at) <= new Date();
  return (
    <Tooltip title={`生效：${doc.effective_at ?? '不限'}；失效：${doc.expires_at ?? '不限'}`}>
      <span className={expired ? 'document-expired' : ''}>
        {expired ? '已过期' : doc.effective_at || doc.expires_at ? '已设置有效期' : '长期有效'}
      </span>
    </Tooltip>
  );
}

/** 两种视图共用当前页数据和动作，移动端不把操作栏放到屏幕外。 */
export default function DocumentList(props: DocumentListProps) {
  const { documents, selectedIds, canSelect, onSelect } = props;
  const screens = Grid.useBreakpoint();
  const pagination = {
    current: props.page,
    pageSize: props.pageSize,
    total: props.total,
    showSizeChanger: true,
    pageSizeOptions: ['10', '20', '30', '50'],
    showTotal: (total: number) => `共 ${total} 个文档`,
    onChange: props.onPageChange,
  };
  if (screens.md === false)
    return (
      <Spin spinning={props.loading}>
        <div className="document-mobile-list">
          {canSelect && documents.length > 0 && (
            <Checkbox
              checked={selectedIds.length === documents.length}
              indeterminate={selectedIds.length > 0 && selectedIds.length < documents.length}
              onChange={(event) => onSelect(event.target.checked ? documents.map((doc) => doc.doc_id) : [])}
            >
              选择本页文档
            </Checkbox>
          )}
          {documents.map((doc) => (
            <article key={doc.doc_id} className="document-mobile-item" aria-label={doc.file_name}>
              <header>
                {canSelect && (
                  <Checkbox
                    aria-label={`选择 ${doc.file_name}`}
                    checked={selectedIds.includes(doc.doc_id)}
                    onChange={(event) =>
                      onSelect(
                        event.target.checked
                          ? [...selectedIds, doc.doc_id]
                          : selectedIds.filter((id) => id !== doc.doc_id),
                      )
                    }
                  />
                )}
                <strong>{doc.file_name}</strong>
              </header>
              <small>
                {doc.file_ext?.toUpperCase()} · {formatFileSize(doc.file_size)}
                {doc.restricted && ' · 受限文档'}
              </small>
              <Space wrap size={[4, 6]}>
                <ProcessingState doc={doc} />
                <PublishingState doc={doc} />
                {doc.config_stale && <Tag color="warning">配置待更新</Tag>}
              </Space>
              <footer>
                <ValidityState doc={doc} />
                {props.actions(doc)}
              </footer>
            </article>
          ))}
          {!props.loading && documents.length === 0 && <Empty description="暂无文档" />}
        </div>
        <Pagination {...pagination} size="small" className="document-mobile-pagination" />
      </Spin>
    );
  return (
    <Table<KbDocument>
      className="document-management-table"
      rowKey="doc_id"
      loading={props.loading}
      dataSource={documents}
      scroll={{ x: 760 }}
      pagination={pagination}
      rowSelection={
        canSelect
          ? {
              getCheckboxProps: (doc) => ({ name: doc.doc_id, 'aria-label': `选择 ${doc.file_name}` }),
              selectedRowKeys: selectedIds,
              onChange: (keys) => onSelect(keys as string[]),
              selections: [Table.SELECTION_ALL, Table.SELECTION_INVERT, Table.SELECTION_NONE],
            }
          : undefined
      }
      columns={[
        {
          title: '文档',
          dataIndex: 'file_name',
          render: (_, doc) => (
            <div className="document-name">
              <strong>{doc.file_name}</strong>
              <small>
                {doc.file_ext?.toUpperCase()} · {formatFileSize(doc.file_size)}
                {doc.restricted && ' · 受限文档'}
              </small>
            </div>
          ),
        },
        { title: '处理状态', width: 110, render: (_, doc) => <ProcessingState doc={doc} /> },
        { title: '发布状态', width: 105, render: (_, doc) => <PublishingState doc={doc} /> },
        {
          title: '有效期 / 配置',
          width: 130,
          render: (_, doc) => (
            <div className="document-name">
              <ValidityState doc={doc} />
              <small>{doc.config_stale ? <Tag color="warning">配置待更新</Tag> : '索引配置最新'}</small>
            </div>
          ),
        },
        { title: '操作', width: 130, fixed: 'right', render: (_, doc) => props.actions(doc) },
      ]}
    />
  );
}

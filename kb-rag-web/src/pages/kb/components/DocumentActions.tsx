import { MoreOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Space } from 'antd';
import type { MenuProps } from 'antd';
import type { KbDocument } from '../../../api/types';

interface DocumentActionsProps {
  doc: KbDocument;
  canWrite: boolean;
  canReview: boolean;
  deleting: boolean;
  onView: () => void;
  onVersions: () => void;
  onPreview: () => void;
  onReview: () => void;
  onSubmitReview: () => Promise<void>;
  onValidity: () => void;
  onVisibility: () => void;
  onReindex: () => Promise<void>;
  onDelete: () => Promise<void>;
}

/** 一项主要操作加二级菜单；权限分别对应文档写入和审核接口。 */
export default function DocumentActions(props: DocumentActionsProps) {
  const { doc, canWrite, canReview, onView, onReview, onPreview } = props;
  const { modal } = App.useApp();
  const failed = Boolean(doc.fail_reason);
  const reviewPending = canReview && doc.publish_status === 'PENDING_REVIEW';
  const previewPending = canWrite && doc.process_status === 'PENDING_CONFIRM';
  const showFailure = () =>
    modal.info({ title: `${doc.file_name} · 处理失败`, content: doc.fail_reason, okText: '知道了' });
  const items: MenuProps['items'] = [
    { key: 'view', label: '查看分片', onClick: onView },
    { key: 'versions', label: '版本历史', onClick: props.onVersions },
    ...(canWrite && ['DRAFT', 'REJECTED'].includes(doc.publish_status)
      ? [
          {
            key: 'submit',
            label: '提交审核',
            onClick: () => {
              void props.onSubmitReview();
            },
          },
        ]
      : []),
    ...(canReview
      ? [
          { key: 'validity', label: '设置有效期', onClick: props.onValidity },
          { key: 'visibility', label: '设置可见性', onClick: props.onVisibility },
        ]
      : []),
    ...(canWrite
      ? [
          { type: 'divider' as const },
          {
            key: 'reindex',
            label: '重新处理',
            onClick: () =>
              modal.confirm({
                title: `重新处理 ${doc.file_name}？`,
                content: '将重新解析、切分并写入索引。',
                okText: '重新处理',
                cancelText: '取消',
                onOk: props.onReindex,
              }),
          },
          {
            key: 'delete',
            label: '移入回收站',
            danger: true,
            onClick: () =>
              modal.confirm({
                title: `将 ${doc.file_name} 移入回收站？`,
                content: '文档将立即从检索中下线，可在回收站还原。超过保留期后自动清除。',
                okText: '移入回收站',
                cancelText: '取消',
                okButtonProps: { danger: true },
                onOk: props.onDelete,
              }),
          },
        ]
      : []),
  ];
  return (
    <Space size={4} className="document-row-actions">
      <Button
        size="small"
        type="link"
        loading={props.deleting}
        onClick={reviewPending ? onReview : previewPending ? onPreview : failed ? showFailure : onView}
      >
        {reviewPending ? '审核' : previewPending ? '预览确认' : failed ? '查看原因' : '查看'}
      </Button>
      <Dropdown trigger={['click']} menu={{ items }}>
        <Button size="small" type="text" icon={<MoreOutlined />} aria-label={`${doc.file_name} 更多操作`} />
      </Dropdown>
    </Space>
  );
}

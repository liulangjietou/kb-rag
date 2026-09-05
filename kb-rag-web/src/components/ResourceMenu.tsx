import { MoreOutlined } from '@ant-design/icons';
import { App, Button, Dropdown } from 'antd';
import type { MenuProps } from 'antd';

interface ResourceMenuProps {
  name: string;
  onEdit?: () => void;
  onDelete?: () => Promise<void>;
  deleteDescription: string;
}

/** 资源目录共用的次要操作；调用方按各资源的实际权限提供动作。 */
export default function ResourceMenu({ name, onEdit, onDelete, deleteDescription }: ResourceMenuProps) {
  const { modal } = App.useApp();
  if (!onEdit && !onDelete) return null;
  const items: MenuProps['items'] = [
    ...(onEdit ? [{ key: 'edit', label: '编辑信息', onClick: onEdit }] : []),
    ...(onDelete
      ? [
          {
            key: 'delete',
            label: '删除',
            danger: true,
            onClick: () =>
              modal.confirm({
                title: `删除 ${name}？`,
                content: deleteDescription,
                okText: '删除',
                cancelText: '取消',
                okButtonProps: { danger: true },
                onOk: onDelete,
              }),
          },
        ]
      : []),
  ];
  return (
    <Dropdown menu={{ items }} trigger={['click']}>
      <Button type="text" size="small" icon={<MoreOutlined />} aria-label={`${name} 更多操作`} />
    </Dropdown>
  );
}

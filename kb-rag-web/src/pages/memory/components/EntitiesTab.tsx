// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { SearchOutlined } from '@ant-design/icons';
import {
  Button,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Popconfirm,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  deleteMemoryNode,
  listMemoryProfiles,
  pageMemoryEntities,
  pageMemoryNodes,
} from '../../../api/memory';
import type { MemoryEntity, MemoryNode, MemoryProfile } from '../../../api/types';

const ENTITY_PAGE_SIZE = 10;
const NODE_PAGE_SIZE = 10;

interface Props {
  libraryId: string;
  canWrite: boolean;
  onChanged: () => void;
}

/**
 * Memory entities tab: one row per user_id aggregated over its nodes; drilling into a row opens
 * a drawer with the entity's paged memory nodes (deletable) and its extracted profiles.
 */
export default function EntitiesTab({ libraryId, canWrite, onChanged }: Props) {
  const [entities, setEntities] = useState<MemoryEntity[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);

  // Drawer state for the selected entity.
  const [activeUserId, setActiveUserId] = useState<string | null>(null);
  const [nodes, setNodes] = useState<MemoryNode[]>([]);
  const [nodeTotal, setNodeTotal] = useState(0);
  const [nodePage, setNodePage] = useState(1);
  const [nodesLoading, setNodesLoading] = useState(false);
  const [profiles, setProfiles] = useState<MemoryProfile[]>([]);
  const [profilesLoading, setProfilesLoading] = useState(false);

  const load = useCallback(
    async (targetPage: number, kw: string) => {
      setLoading(true);
      try {
        const result = await pageMemoryEntities(libraryId, {
          user_id: kw || undefined,
          page: targetPage,
          size: ENTITY_PAGE_SIZE,
        });
        setEntities(result.items);
        setTotal(result.total);
        setPage(result.page);
      } finally {
        setLoading(false);
      }
    },
    [libraryId],
  );

  useEffect(() => {
    load(1, '');
  }, [load]);

  const loadNodes = useCallback(
    async (userId: string, targetPage: number) => {
      setNodesLoading(true);
      try {
        const result = await pageMemoryNodes(libraryId, {
          user_id: userId,
          page: targetPage,
          size: NODE_PAGE_SIZE,
        });
        setNodes(result.memory_nodes);
        setNodeTotal(result.total);
        setNodePage(result.page);
      } finally {
        setNodesLoading(false);
      }
    },
    [libraryId],
  );

  const loadProfiles = useCallback(
    async (userId: string) => {
      setProfilesLoading(true);
      try {
        setProfiles(await listMemoryProfiles(libraryId, { user_id: userId }));
      } finally {
        setProfilesLoading(false);
      }
    },
    [libraryId],
  );

  const openEntity = (userId: string) => {
    setActiveUserId(userId);
    loadNodes(userId, 1);
    loadProfiles(userId);
  };

  const handleDeleteNode = async (nodeId: string) => {
    if (!activeUserId) return;
    await deleteMemoryNode(libraryId, nodeId);
    message.success('记忆节点已删除');
    loadNodes(activeUserId, nodePage);
    load(page, keyword);
    onChanged();
  };

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="按用户 ID 精确过滤"
          style={{ width: 260 }}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={() => load(1, keyword)}
          onClear={() => load(1, '')}
        />
      </div>

      <Table<MemoryEntity>
        rowKey="user_id"
        loading={loading}
        dataSource={entities}
        pagination={{
          current: page,
          pageSize: ENTITY_PAGE_SIZE,
          total,
          showSizeChanger: false,
          onChange: (p) => load(p, keyword),
        }}
        columns={[
          {
            title: '用户 ID',
            dataIndex: 'user_id',
            render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
          },
          { title: '记忆节点数', dataIndex: 'node_count', width: 120 },
          { title: '最近更新', dataIndex: 'updated_at', width: 180 },
          {
            title: '操作',
            width: 120,
            render: (_, record) => (
              <Button size="small" onClick={() => openEntity(record.user_id)}>
                查看记忆
              </Button>
            ),
          },
        ]}
      />

      <Drawer
        rootClassName="catalog-eval-drawer"
        title={
          <Space>
            记忆实体
            {activeUserId && <Typography.Text code>{activeUserId}</Typography.Text>}
          </Space>
        }
        width={720}
        open={activeUserId !== null}
        onClose={() => setActiveUserId(null)}
        destroyOnClose
      >
        <Tabs
          defaultActiveKey="nodes"
          items={[
            {
              key: 'nodes',
              label: '记忆节点',
              children: (
                <Table<MemoryNode>
                  rowKey="memory_node_id"
                  loading={nodesLoading}
                  dataSource={nodes}
                  size="small"
                  pagination={{
                    current: nodePage,
                    pageSize: NODE_PAGE_SIZE,
                    total: nodeTotal,
                    showSizeChanger: false,
                    onChange: (p) => activeUserId && loadNodes(activeUserId, p),
                  }}
                  columns={[
                    {
                      title: '记忆内容',
                      dataIndex: 'content',
                      render: (v: string) => (
                        <Typography.Paragraph style={{ marginBottom: 0 }} ellipsis={{ rows: 3, expandable: true }}>
                          {v}
                        </Typography.Paragraph>
                      ),
                    },
                    {
                      title: '来源',
                      dataIndex: 'source',
                      width: 90,
                      render: (v: string) =>
                        v === 'EXTRACTED' ? <Tag color="blue">模型抽取</Tag> : <Tag color="green">直接写入</Tag>,
                    },
                    {
                      title: '过期时间',
                      dataIndex: 'expire_at',
                      width: 120,
                      render: (v: string | null) => v ?? '永不过期',
                    },
                    { title: '更新时间', dataIndex: 'updated_at', width: 120 },
                    ...(canWrite
                      ? [
                          {
                            title: '操作',
                            width: 70,
                            render: (_: unknown, record: MemoryNode) => (
                              <Popconfirm
                                title="确认删除该记忆节点？"
                                okText="删除"
                                okType="danger"
                                cancelText="取消"
                                onConfirm={() => handleDeleteNode(record.memory_node_id)}
                              >
                                <Button size="small" danger type="link">
                                  删除
                                </Button>
                              </Popconfirm>
                            ),
                          },
                        ]
                      : []),
                  ]}
                />
              ),
            },
            {
              key: 'profiles',
              label: '用户画像',
              children: profilesLoading ? null : profiles.length === 0 ? (
                <Empty description="该实体暂无画像数据，配置用户画像规则后由 AddMemory 自动抽取" />
              ) : (
                <Space direction="vertical" style={{ width: '100%' }} size="large">
                  {profiles.map((profile) => (
                    <div key={profile.rule_id}>
                      <Typography.Title level={5} style={{ marginTop: 0 }}>
                        {profile.rule_name}
                        {profile.updated_at && (
                          <Typography.Text type="secondary" style={{ marginLeft: 8, fontWeight: 'normal', fontSize: 12 }}>
                            更新于 {profile.updated_at}
                          </Typography.Text>
                        )}
                      </Typography.Title>
                      <Descriptions bordered size="small" column={2}>
                        {profile.attributes.map((attr) => (
                          <Descriptions.Item key={attr.name} label={attr.name}>
                            {attr.value ?? <Typography.Text type="secondary">未抽取</Typography.Text>}
                          </Descriptions.Item>
                        ))}
                      </Descriptions>
                    </div>
                  ))}
                </Space>
              ),
            },
          ]}
        />
      </Drawer>
    </div>
  );
}

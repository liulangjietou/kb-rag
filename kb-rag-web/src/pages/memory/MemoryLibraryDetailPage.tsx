// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { ApiOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Space, Spin, Tabs, Tag, Typography } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { getMemoryLibrary } from '../../api/memory';
import type { MemoryLibraryDetail } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import ApiCallDrawer from './components/ApiCallDrawer';
import EntitiesTab from './components/EntitiesTab';
import FragmentRulesTab from './components/FragmentRulesTab';
import MemoryKeysTab from './components/MemoryKeysTab';
import ProfileRulesTab from './components/ProfileRulesTab';
import SearchDebugTab from './components/SearchDebugTab';

/**
 * Memory library detail: header stats plus five self-loading tabs (fragment rules, profile
 * rules, entities, search debug, memory keys). Each tab refetches on its own; the header
 * counters refresh whenever a tab reports a mutation via onChanged.
 */
export default function MemoryLibraryDetailPage() {
  const { libraryId } = useParams<{ libraryId: string }>();
  const navigate = useNavigate();
  const { can } = useAuth();
  const canWrite = can(PERMISSIONS.MEMORY_WRITE);
  const [detail, setDetail] = useState<MemoryLibraryDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [apiDrawerOpen, setApiDrawerOpen] = useState(false);

  const loadDetail = useCallback(async () => {
    if (!libraryId) return;
    const data = await getMemoryLibrary(libraryId);
    setDetail(data);
  }, [libraryId]);

  useEffect(() => {
    setLoading(true);
    loadDetail().finally(() => setLoading(false));
  }, [loadDetail]);

  if (loading || !detail || !libraryId) {
    return <Spin style={{ display: 'block', margin: '80px auto' }} />;
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <Space>
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/memory')} />
          <Typography.Title level={4} style={{ margin: 0 }}>
            {detail.name}
          </Typography.Title>
          <Tag>{detail.library_id}</Tag>
        </Space>
        <Space size="large">
          <Typography.Text type="secondary">记忆节点 {detail.node_count}</Typography.Text>
          <Typography.Text type="secondary">记忆实体 {detail.entity_count}</Typography.Text>
          <Button icon={<ApiOutlined />} onClick={() => setApiDrawerOpen(true)}>
            API 调用
          </Button>
        </Space>
      </div>
      {detail.description && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          {detail.description}
        </Typography.Paragraph>
      )}

      <Tabs
        defaultActiveKey="fragment-rules"
        items={[
          {
            key: 'fragment-rules',
            label: '记忆片段规则',
            children: (
              <FragmentRulesTab libraryId={libraryId} canWrite={canWrite} onChanged={loadDetail} />
            ),
          },
          {
            key: 'profile-rules',
            label: '用户画像规则',
            children: (
              <ProfileRulesTab libraryId={libraryId} canWrite={canWrite} onChanged={loadDetail} />
            ),
          },
          {
            key: 'entities',
            label: '记忆实体',
            children: <EntitiesTab libraryId={libraryId} canWrite={canWrite} onChanged={loadDetail} />,
          },
          {
            key: 'search-debug',
            label: '检索调试',
            children: <SearchDebugTab libraryId={libraryId} />,
          },
          {
            key: 'keys',
            label: 'Memory Key',
            children: <MemoryKeysTab libraryId={libraryId} canWrite={canWrite} />,
          },
        ]}
      />

      <ApiCallDrawer open={apiDrawerOpen} onClose={() => setApiDrawerOpen(false)} detail={detail} />
    </div>
  );
}

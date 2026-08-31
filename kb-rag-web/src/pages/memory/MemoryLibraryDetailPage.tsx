// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { ApiOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Space, Spin, Tabs, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { getMemoryLibrary } from '../../api/memory';
import type { MemoryLibraryDetail } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import PageHeader from '../../components/PageHeader';
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
    <div className="catalog-eval-page catalog-detail-page">
      <PageHeader
        eyebrow="MEMORY WORKSPACE / 记忆工作台"
        title={detail.name}
        description={detail.description || '配置记忆采集规则、实体关系、检索调试与智能体访问密钥。'}
        before={
          <Button
            type="text"
            className="catalog-back-button"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/memory')}
          >
            返回记忆库
          </Button>
        }
        actions={
          <Space className="catalog-context-actions" size="middle" wrap>
            <span className="catalog-context-metric">
              <b>{detail.node_count}</b> 记忆节点
            </span>
            <span className="catalog-context-metric">
              <b>{detail.entity_count}</b> 记忆实体
            </span>
            <Button icon={<ApiOutlined />} onClick={() => setApiDrawerOpen(true)}>
              API 调用
            </Button>
          </Space>
        }
      />
      <Tag className="catalog-context-id">{detail.library_id}</Tag>

      <Tabs
        className="catalog-workbench-tabs"
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

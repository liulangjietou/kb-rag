// Author: owlzhangfq@gmail.com
import {
  AppstoreOutlined,
  ArrowRightOutlined,
  AuditOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { Alert, Button, Empty, Skeleton, Tag } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { listApps } from '../api/app';
import { listKnowledgeBases } from '../api/kb';
import { listRegistrationReviews } from '../api/registration';
import type { KbApp, KnowledgeBase } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { PERMISSIONS } from '../auth/permissions';
import { useModelStatus } from '../context/ModelStatusContext';
import '../styles/home.css';

type LoadState = 'idle' | 'loading' | 'success' | 'error';

interface HomeResource {
  key: string;
  kind: '知识库' | '应用';
  name: string;
  detail: string;
  path: string;
  updatedAt: string;
}

function dateLabel(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? '时间未知'
    : new Intl.DateTimeFormat('zh-CN', {
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      }).format(date);
}

function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 6) return '夜深了';
  if (hour < 12) return '早上好';
  if (hour < 18) return '下午好';
  return '晚上好';
}

/** 所有指标来自已授权的真实接口；拿不到的数据明确显示破折号，不用设计稿样例填充。 */
export default function HomePage() {
  const navigate = useNavigate();
  const { displayName, can } = useAuth();
  const {
    modelStatus,
    loading: modelLoading,
    error: modelError,
    refresh: refreshModelStatus,
  } = useModelStatus();
  const canReadKb = can(PERMISSIONS.KB_READ);
  const canReadApps = can(PERMISSIONS.APP_READ);
  const canReviewRegistrations = can(PERMISSIONS.USER_MANAGE) && can(PERMISSIONS.TENANT_MANAGE);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [apps, setApps] = useState<KbApp[]>([]);
  const [pendingReviews, setPendingReviews] = useState<number | null>(null);
  const [kbState, setKbState] = useState<LoadState>('idle');
  const [appState, setAppState] = useState<LoadState>('idle');
  const [reviewState, setReviewState] = useState<LoadState>('idle');
  const [reloadGeneration, setReloadGeneration] = useState(0);
  const [query, setQuery] = useState('');
  const requestSequenceRef = useRef(0);

  useEffect(() => {
    const sequence = ++requestSequenceRef.current;
    if (canReadKb) {
      setKbState('loading');
    } else {
      setKnowledgeBases([]);
      setKbState('idle');
    }
    if (canReadApps) {
      setAppState('loading');
    } else {
      setApps([]);
      setAppState('idle');
    }
    if (canReviewRegistrations) {
      setReviewState('loading');
    } else {
      setPendingReviews(null);
      setReviewState('idle');
    }

    const loadKb = canReadKb
      ? listKnowledgeBases()
          .then((items) => {
            if (requestSequenceRef.current === sequence) {
              setKnowledgeBases(items);
              setKbState('success');
            }
          })
          .catch(() => {
            if (requestSequenceRef.current === sequence) {
              setKnowledgeBases([]);
              setKbState('error');
            }
          })
      : Promise.resolve();
    const loadApps = canReadApps
      ? listApps()
          .then((items) => {
            if (requestSequenceRef.current === sequence) {
              setApps(items);
              setAppState('success');
            }
          })
          .catch(() => {
            if (requestSequenceRef.current === sequence) {
              setApps([]);
              setAppState('error');
            }
          })
      : Promise.resolve();
    const loadReviews = canReviewRegistrations
      ? listRegistrationReviews({ status: 'PENDING', page: 1, size: 1 })
          .then((page) => {
            if (requestSequenceRef.current === sequence) {
              setPendingReviews(page.total);
              setReviewState('success');
            }
          })
          .catch(() => {
            if (requestSequenceRef.current === sequence) {
              setPendingReviews(null);
              setReviewState('error');
            }
          })
      : Promise.resolve();

    void Promise.allSettled([loadKb, loadApps, loadReviews]);
    return () => {
      requestSequenceRef.current += 1;
    };
  }, [canReadApps, canReadKb, canReviewRegistrations, reloadGeneration]);

  const resources = useMemo<HomeResource[]>(
    () =>
      [
        ...(canReadKb ? knowledgeBases : []).map((kb) => ({
          key: `kb-${kb.kb_id}`,
          kind: '知识库' as const,
          name: kb.name,
          detail: kb.description || '暂无描述',
          path: `/kb/${kb.kb_id}`,
          updatedAt: kb.created_at,
        })),
        ...(canReadApps ? apps : []).map((app) => ({
          key: `app-${app.app_id}`,
          kind: '应用' as const,
          name: app.name,
          detail: app.description || '暂无描述',
          path: `/apps/${app.app_id}`,
          updatedAt: app.updated_at,
        })),
      ].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)),
    [apps, canReadApps, canReadKb, knowledgeBases],
  );

  const matches = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return keyword
      ? resources
          .filter((resource) => `${resource.name} ${resource.detail}`.toLowerCase().includes(keyword))
          .slice(0, 6)
      : [];
  }, [query, resources]);

  const metrics = [
    {
      label: '可访问知识库',
      value:
        !canReadKb || kbState === 'error' ? '—' : kbState === 'loading' ? '加载中' : knowledgeBases.length,
      detail: '当前账号授权范围',
    },
    {
      label: '应用',
      value: !canReadApps || appState === 'error' ? '—' : appState === 'loading' ? '加载中' : apps.length,
      detail:
        canReadApps && appState === 'success'
          ? `其中 ${apps.filter((app) => app.released_version_id).length} 个已有发布版本`
          : '按已授权应用统计',
    },
    {
      label: '待审核申请',
      value:
        !canReviewRegistrations || reviewState === 'error'
          ? '—'
          : reviewState === 'loading'
            ? '加载中'
            : (pendingReviews ?? 0),
      detail: canReviewRegistrations ? '审核后分配租户与角色' : '当前账号无审核权限',
    },
    {
      label: '检索能力',
      value: modelLoading
        ? '加载中'
        : modelStatus
          ? modelStatus.embedding_configured
            ? '混合'
            : 'BM25'
          : '—',
      detail: modelStatus
        ? modelStatus.embedding_configured
          ? '向量与关键词检索已配置'
          : '嵌入模型尚未配置'
        : '模型配置暂不可用',
    },
  ];

  const partialFailure =
    (canReadKb && kbState === 'error') ||
    (canReadApps && appState === 'error') ||
    (canReviewRegistrations && reviewState === 'error') ||
    modelError;
  const resourcesLoading = (canReadKb && kbState === 'loading') || (canReadApps && appState === 'loading');
  const quickActions = [
    canReadKb
      ? {
          key: 'kb',
          label: '进入知识库',
          description: '查看已授权的知识资产',
          path: '/kb',
          icon: <DatabaseOutlined />,
        }
      : null,
    canReadApps
      ? {
          key: 'apps',
          label: '进入应用中心',
          description: '查看应用与发布版本',
          path: '/apps',
          icon: <AppstoreOutlined />,
        }
      : null,
    can(PERMISSIONS.SEARCH_DEBUG)
      ? {
          key: 'search',
          label: '检索调试',
          description: '验证召回结果与引用',
          path: '/search',
          icon: <SearchOutlined />,
        }
      : null,
    can(PERMISSIONS.EVAL_READ)
      ? {
          key: 'eval',
          label: '查看质量评测',
          description: '检查评测数据和运行结果',
          path: '/eval',
          icon: <ExperimentOutlined />,
        }
      : null,
    canReviewRegistrations
      ? {
          key: 'review',
          label: '注册审核',
          description: `${pendingReviews ?? '—'} 个申请待处理`,
          path: '/users/registration-reviews',
          icon: <AuditOutlined />,
        }
      : null,
  ].filter(Boolean) as Array<{
    key: string;
    label: string;
    description: string;
    path: string;
    icon: React.ReactNode;
  }>;

  return (
    <div className="atlas-home">
      <header className="atlas-home__heading">
        <div>
          <h1>工作概览</h1>
          <p>
            {greeting()}，{displayName ?? '知识工作者'}。继续维护知识，或验证应用的回答。
          </p>
        </div>
      </header>

      {partialFailure && (
        <Alert
          className="atlas-home__alert"
          type="warning"
          showIcon
          message="部分首页数据暂不可用"
          description="页面保留已成功返回的真实数据；失败或无权访问的指标显示为 —。"
          action={
            <Button
              size="small"
              icon={<ReloadOutlined />}
              onClick={() => {
                setReloadGeneration((current) => current + 1);
                refreshModelStatus();
              }}
            >
              重试
            </Button>
          }
        />
      )}

      <section className="home-metrics" aria-label="工作概览指标">
        {metrics.map((metric) => (
          <div key={metric.label}>
            <span>{metric.label}</span>
            <strong>{metric.value}</strong>
            <small>{metric.detail}</small>
          </div>
        ))}
      </section>

      <div className="atlas-home__grid">
        <div className="home-work-column">
          <section className="atlas-panel">
            <header className="atlas-panel__head">
              <div>
                <h2>继续工作</h2>
                <p>知识库按创建时间、应用按更新时间排列</p>
              </div>
              <div className="atlas-command-search">
                <SearchOutlined aria-hidden="true" />
                <input
                  value={query}
                  type="search"
                  placeholder="搜索已授权的知识库或应用…"
                  aria-label="搜索已授权的知识库或应用"
                  onChange={(event) => setQuery(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && matches[0]) {
                      navigate(matches[0].path);
                    }
                    if (event.key === 'Escape') setQuery('');
                  }}
                />
                <kbd>↵</kbd>
                {matches.length > 0 && (
                  <ul
                    id="atlas-home-search-results"
                    className="atlas-command-search__results"
                    aria-label="匹配的知识资源"
                  >
                    {matches.map((resource) => (
                      <li key={resource.key}>
                        <button type="button" onClick={() => navigate(resource.path)}>
                          <span>{resource.kind}</span>
                          <strong>{resource.name}</strong>
                          <ArrowRightOutlined />
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </header>
            {resourcesLoading ? (
              <div className="atlas-panel__loading">
                <Skeleton active paragraph={{ rows: 4 }} />
              </div>
            ) : resources.length === 0 && (kbState === 'error' || appState === 'error') ? (
              <Alert
                className="atlas-panel__resource-error"
                type="error"
                showIcon
                message="资源数据加载失败"
                description="这不是空数据，请使用上方“重试”重新读取。"
              />
            ) : resources.length === 0 ? (
              <Empty
                className="atlas-panel__empty"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="当前没有可展示的知识库或应用"
              />
            ) : (
              <ul className="atlas-work-list">
                {resources.slice(0, 5).map((resource) => (
                  <li key={resource.key}>
                    <button type="button" onClick={() => navigate(resource.path)}>
                      <span className={`atlas-work-list__icon${resource.kind === '应用' ? ' is-app' : ''}`}>
                        {resource.kind === '应用' ? <AppstoreOutlined /> : <DatabaseOutlined />}
                      </span>
                      <span className="atlas-work-list__copy">
                        <strong>{resource.name}</strong>
                        <small>{resource.detail}</small>
                      </span>
                      <span className="atlas-work-list__meta">
                        <Tag>{resource.kind}</Tag>
                        <small>
                          {resource.kind === '知识库' ? '创建于 ' : '更新于 '}
                          {dateLabel(resource.updatedAt)}
                        </small>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="home-provenance" aria-label="知识证据路径">
            <div>
              <h2>沿着证据，验证每个回答</h2>
              <p>从文档片段到应用引用，始终保留可回查的来源。</p>
            </div>
            <ol>
              <li>源文档</li>
              <li>知识片段</li>
              <li>召回证据</li>
              <li>引用回答</li>
            </ol>
            {can(PERMISSIONS.SEARCH_DEBUG) && (
              <Button onClick={() => navigate('/search')} icon={<SearchOutlined />}>
                开始检索
              </Button>
            )}
          </section>
        </div>
        <aside className="home-side-column">
          {(canReviewRegistrations || can(PERMISSIONS.EVAL_READ)) && (
            <section className="atlas-panel home-attention">
              <header className="atlas-panel__head">
                <h2>需要处理</h2>
              </header>
              {canReviewRegistrations && (
                <div>
                  <AuditOutlined />
                  <section>
                    <h3>注册申请待审核</h3>
                    <p>
                      {reviewState === 'success'
                        ? `${pendingReviews ?? 0} 个申请等待审核与权限分配`
                        : '审核数据暂不可用'}
                    </p>
                    <Button type="link" onClick={() => navigate('/users/registration-reviews')}>
                      处理申请 <ArrowRightOutlined />
                    </Button>
                  </section>
                </div>
              )}
              {can(PERMISSIONS.EVAL_READ) && (
                <div>
                  <ExperimentOutlined />
                  <section>
                    <h3>验证发布质量</h3>
                    <p>查看评测结果，再发布应用版本。</p>
                    <Button type="link" onClick={() => navigate('/eval')}>
                      进入评测中心 <ArrowRightOutlined />
                    </Button>
                  </section>
                </div>
              )}
            </section>
          )}
          <section className="atlas-panel atlas-quick-actions">
            <header className="atlas-panel__head">
              <div>
                <h2>常用入口</h2>
                <p>快速进入日常工作</p>
              </div>
            </header>
            {quickActions.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用功能，请联系管理员分配角色" />
            ) : (
              <div className="atlas-quick-actions__list">
                {quickActions.map((action) => (
                  <Button
                    key={action.key}
                    className="atlas-quick-action"
                    type="text"
                    onClick={() => navigate(action.path)}
                  >
                    <span className="atlas-quick-action__icon">{action.icon}</span>
                    <span>
                      <strong>{action.label}</strong>
                      <small>{action.description}</small>
                    </span>
                    <ArrowRightOutlined />
                  </Button>
                ))}
              </div>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}

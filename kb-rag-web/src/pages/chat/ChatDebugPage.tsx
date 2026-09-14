// Author: owlzhangfq@gmail.com
import { useEffect, useRef, useState } from 'react';
import { PauseOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Input, Select, Skeleton, Space, Switch, Tag, Typography } from 'antd';
import { chatPreview, listPreviewApps, streamChatPreview } from '../../api/app';
import { listKnowledgeBases } from '../../api/kb';
import ImagePicker, { toImagesPayload, type PickedImage } from '../../components/ImagePicker';
import PageHeader from '../../components/PageHeader';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import type { AppPreviewOption, ChatMessage, KnowledgeBase, RetrievalNode } from '../../api/types';
import { kbNameOf } from '../../utils/kbRefs';
import { APP_VERSION_STATUS_META, describeDegradedReason } from '../../utils/statusMeta';
import { readChatDiagnostics, type ChatDiagnostics } from '../../api/chatDiagnostics';
import ChatDiagnosticsPanel, { type BrowserChatTiming } from './ChatDiagnosticsPanel';

interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
  references?: RetrievalNode[];
  degraded?: string[];
  routedKbIds?: string[];
  requestId?: string;
  error?: { code: string; message: string };
  stopped?: boolean;
  diagnostics?: ChatDiagnostics;
  browserTiming?: BrowserChatTiming;
}

/** 问答工作台按轮次保存回答与引用，取消或切换应用后旧请求不能继续写入新会话。 */
export default function ChatDebugPage() {
  const auth = useAuth();
  const scope = JSON.stringify([auth.token, auth.permissions, auth.kbScopeAll, auth.kbIds]);
  return <ChatDebugContent key={scope} />;
}

function ChatDebugContent() {
  const { can } = useAuth();
  const canReadKb = can(PERMISSIONS.KB_READ);
  const [apps, setApps] = useState<AppPreviewOption[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState(false);
  const [catalogRefresh, setCatalogRefresh] = useState(0);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [appId, setAppId] = useState<string | null>(null);
  const [appVersion, setAppVersion] = useState<string>('');
  const [streamEnabled, setStreamEnabled] = useState(true);
  const [input, setInput] = useState('');
  const [images, setImages] = useState<PickedImage[]>([]);
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [sending, setSending] = useState(false);
  const listEndRef = useRef<HTMLDivElement>(null);
  const requestRef = useRef<AbortController | null>(null);
  const requestSequence = useRef(0);
  const requestTiming = useRef<{ startedAt: number; streamed: boolean; firstDeltaMs?: number } | null>(null);
  const [referenceTurn, setReferenceTurn] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    setCatalogLoading(true);
    setCatalogError(false);
    listPreviewApps().then((list) => {
      if (!active) return;
      setApps(list);
      setAppId(list[0]?.app_id ?? null);
      setAppVersion(list[0]?.versions[0]?.app_version_id ?? '');
    }).catch(() => {
      if (active) setCatalogError(true);
    }).finally(() => {
      if (active) setCatalogLoading(false);
    });
    return () => { active = false; };
  }, [catalogRefresh]);

  useEffect(() => {
    let active = true;
    if (canReadKb) listKnowledgeBases().then((list) => {
      if (active) setKbs(list);
    }).catch(() => { /* 名称补齐失败不阻断问答，证据仍显示业务 ID。 */ });
    else setKbs([]);
    return () => { active = false; };
  }, [canReadKb]);

  useEffect(() => {
    listEndRef.current?.scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
      block: 'nearest',
    });
  }, [turns]);

  useEffect(
    () => () => {
      requestSequence.current += 1;
      requestRef.current?.abort();
    },
    [],
  );

  const stop = () => {
    const timing = requestTiming.current;
    const browserTiming = timing ? { streamed: timing.streamed, firstDeltaMs: timing.firstDeltaMs,
      totalMs: performance.now() - timing.startedAt } : undefined;
    requestSequence.current += 1;
    requestRef.current?.abort();
    requestRef.current = null;
    requestTiming.current = null;
    setSending(false);
    setTurns((previous) =>
      previous.map((turn, index) =>
        index === previous.length - 1 && turn.role === 'assistant' ? { ...turn, stopped: true, browserTiming } : turn,
      ),
    );
  };

  const newConversation = () => {
    stop();
    setTurns([]);
    setReferenceTurn(null);
  };

  const handleSend = async () => {
    if (!appId || !appVersion || catalogLoading || catalogError || !input.trim() || requestRef.current) return;
    const controller = new AbortController();
    requestRef.current = controller;
    const sequence = ++requestSequence.current;
    const timing: { startedAt: number; streamed: boolean; firstDeltaMs?: number } = {
      startedAt: performance.now(), streamed: streamEnabled,
    };
    requestTiming.current = timing;
    const query = input.trim();
    const history: ChatMessage[] = turns.map((turn) => ({ role: turn.role, content: turn.content }));
    const assistantIndex = turns.length + 1;
    const imagesPayload = toImagesPayload(images);
    setTurns((previous) => [
      ...previous,
      { role: 'user', content: query },
      { role: 'assistant', content: '' },
    ]);
    setReferenceTurn(assistantIndex);
    setInput('');
    setImages([]);
    setSending(true);
    const updateAnswer = (update: (turn: ChatTurn) => ChatTurn) => {
      if (requestSequence.current !== sequence) return;
      setTurns((previous) => previous.map((turn, index) => (index === assistantIndex ? update(turn) : turn)));
    };
    try {
      if (streamEnabled) {
        await streamChatPreview(
          appId,
          { query, messages: history, app_version_id: appVersion, images: imagesPayload },
          {
            onDelta: (delta) => {
              if (delta && timing.firstDeltaMs === undefined) timing.firstDeltaMs = performance.now() - timing.startedAt;
              updateAnswer((turn) => ({ ...turn, content: turn.content + delta }));
            },
            onDiagnostics: (diagnostics) => updateAnswer((turn) => ({ ...turn, diagnostics,
              requestId: diagnostics.request_id ?? turn.requestId })),
            onReferences: (references) => updateAnswer((turn) => ({ ...turn, references })),
            onDone: (requestId, degraded, routedKbIds) =>
              updateAnswer((turn) => ({ ...turn, requestId, degraded, routedKbIds })),
            onError: (error) => updateAnswer((turn) => ({ ...turn, error })),
          },
          controller.signal,
        );
      } else {
        const response = await chatPreview(appId, {
          query,
          messages: history,
          app_version_id: appVersion,
          images: imagesPayload,
        });
        updateAnswer((turn) => ({
          ...turn,
          content: response.answer,
          references: response.references,
          degraded: response.degraded,
          routedKbIds: response.routed_kb_ids,
          requestId: response.request_id,
          diagnostics: readChatDiagnostics(response.diagnostics),
        }));
      }
    } catch {
      if (!controller.signal.aborted)
        updateAnswer((turn) => ({
          ...turn,
          error: { code: 'REQUEST_FAILED', message: '请求失败，请检查应用配置或稍后重试' },
        }));
    } finally {
      if (requestSequence.current === sequence) {
        const browserTiming = { streamed: timing.streamed, firstDeltaMs: timing.firstDeltaMs,
          totalMs: performance.now() - timing.startedAt };
        updateAnswer((turn) => ({ ...turn, browserTiming }));
        requestRef.current = null;
        requestTiming.current = null;
        setSending(false);
      }
    }
  };

  const activeReferences = referenceTurn === null ? [] : (turns[referenceTurn]?.references ?? []);
  const activeVersions = apps.find((app) => app.app_id === appId)?.versions ?? [];
  const inputDisabled = !appId || !appVersion || catalogLoading || catalogError;

  return (
    <div className="knowledge-workbench-page chat-workbench-page">
      <PageHeader
        eyebrow="ANSWER STUDIO"
        title="问答调试"
        description="验证应用回答，逐条核对引用证据和检索来源。"
        actions={
          <Button icon={<PlusOutlined />} onClick={newConversation}>
            新对话
          </Button>
        }
      />

      <Card className="workbench-toolbar-card chat-settings-card" size="small">
        <Space className="chat-settings" wrap>
          <Select
            className="chat-app-select"
            aria-label="调试应用"
            placeholder="请选择应用"
            loading={catalogLoading}
            disabled={catalogLoading || catalogError}
            value={appId ?? undefined}
            options={apps.map((app) => ({ label: app.name, value: app.app_id }))}
            onChange={(value) => {
              setAppId(value);
              setAppVersion(apps.find((app) => app.app_id === value)?.versions[0]?.app_version_id ?? '');
              newConversation();
            }}
          />
          <Select
            className="chat-version-input"
            placeholder="请选择应用版本"
            value={appVersion || undefined}
            disabled={sending || inputDisabled}
            aria-label="应用版本"
            options={activeVersions.map((version) => ({
              value: version.app_version_id,
              label: `${version.version} · ${APP_VERSION_STATUS_META[version.status]?.label ?? version.status}`,
            }))}
            onChange={(value) => {
              setAppVersion(value);
              newConversation();
            }}
          />
          <Space className="chat-stream-toggle">
            <Typography.Text>流式</Typography.Text>
            <Switch
              disabled={sending}
              aria-label="启用流式回答"
              checked={streamEnabled}
              onChange={setStreamEnabled}
            />
          </Space>
        </Space>
        <div className="chat-corpus-note">
          <Tag>当前语料</Tag>
          <Typography.Text type="secondary">按所选版本配置调试，读取当前活动文档。正式调用的语料范围请核对发布快照。</Typography.Text>
        </div>
      </Card>

      <div className="chat-workspace-grid">
        <section className="chat-main-pane" aria-label="调试对话">
          {catalogLoading ? <Card><Skeleton active paragraph={{ rows: 3 }} /></Card> : catalogError ? (
            <Alert type="error" showIcon message="可调试应用加载失败" description="请检查网络连接后重试，输入内容已保留。"
              action={<Button onClick={() => setCatalogRefresh((value) => value + 1)}>重新加载</Button>} />
          ) : !appId ? (
            <Alert
              className="chat-prerequisite"
              type="info"
              showIcon
              message="暂无可调试应用"
              description="需要应用版本已配置，且关联的知识库全部在你的访问范围内。请联系应用管理员。"
            />
          ) : (
            <Card className="chat-transcript-card">
              {turns.length === 0 ? (
                <Empty description="选择应用并发送问题，在右侧查看回答的引用证据" />
              ) : (
                <Space className="chat-transcript" direction="vertical" size={18}>
                  {turns.map((turn, index) => (
                    <div key={index} className={`chat-turn chat-turn--${turn.role}`}>
                      <div className="chat-turn__identity">
                        <Tag color={turn.role === 'user' ? 'blue' : 'default'}>
                          {turn.role === 'user' ? '我' : '助手'}
                        </Tag>
                      </div>
                      <div className="chat-turn__bubble">
                        {turn.content || (turn.stopped ? '已停止生成' : turn.error ? '' : '生成中...')}
                      </div>
                      {turn.stopped && turn.content && <Tag>已停止生成</Tag>}
                      {turn.error && (
                        <Alert
                          style={{ marginTop: 8 }}
                          type="error"
                          showIcon
                          message={`${turn.error.code}`}
                          description={turn.error.message}
                        />
                      )}
                      {turn.degraded && turn.degraded.length > 0 && (
                        <Alert
                          style={{ marginTop: 8 }}
                          type="warning"
                          showIcon
                          message="已降级"
                          description={turn.degraded.map(describeDegradedReason).join('；')}
                        />
                      )}
                      {turn.routedKbIds && turn.routedKbIds.length > 0 && (
                        <Space wrap style={{ marginTop: 8 }}>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            本次检索知识库：
                          </Typography.Text>
                          {turn.routedKbIds.map((kbId) => (
                            <Tag key={kbId} color="processing">
                              {kbNameOf(kbs, kbId)}
                            </Tag>
                          ))}
                        </Space>
                      )}
                      {turn.references && turn.references.length > 0 && (
                        <Button type="link" size="small" onClick={() => setReferenceTurn(index)}>
                          查看 {turn.references.length} 条引用证据
                        </Button>
                      )}
                      {turn.requestId && (
                        <Typography.Text
                          type="secondary"
                          style={{ display: 'block', marginTop: 4, fontSize: 12 }}
                        >
                          request_id: {turn.requestId}
                        </Typography.Text>
                      )}
                      {turn.browserTiming && <ChatDiagnosticsPanel diagnostics={turn.diagnostics}
                        browser={turn.browserTiming} stopped={turn.stopped} failed={!!turn.error} />}
                    </div>
                  ))}
                  <div ref={listEndRef} />
                </Space>
              )}
            </Card>
          )}

          <div className="chat-image-picker">
            <ImagePicker value={images} onChange={setImages} disabled={inputDisabled} />
          </div>
          <Space.Compact className="chat-composer">
            <Input.TextArea
              rows={2}
              placeholder="输入问题，回车发送（Shift+回车换行）"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  handleSend();
                }
              }}
              disabled={inputDisabled}
            />
            <Button
              className="chat-send-button"
              aria-label="发送问题"
              type="primary"
              icon={<SendOutlined />}
              loading={sending}
              disabled={inputDisabled || !input.trim() || sending}
              onClick={handleSend}
            >
              发送
            </Button>
          </Space.Compact>
          {sending && streamEnabled && (
            <Button className="chat-stop-button" icon={<PauseOutlined />} onClick={stop}>
              停止生成
            </Button>
          )}
        </section>
        <aside className="chat-evidence-pane" aria-label="引用证据">
          <header className="workspace-section-heading">
            <h2>引用证据</h2>
            <span>{activeReferences.length} 条</span>
          </header>
          {activeReferences.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="回答引用将在这里展示" />
          ) : (
            activeReferences.map((reference, index) => (
              <Card
                key={reference.chunk_id}
                className="chat-reference-card"
                size="small"
                title={
                  <Space wrap>
                    <Tag>{index + 1}</Tag>
                    <span>{reference.doc_id}</span>
                  </Space>
                }
              >
                {reference.metadata?.kb_id && <Tag>{kbNameOf(kbs, reference.metadata.kb_id)}</Tag>}
                <Typography.Paragraph ellipsis={{ rows: 5, expandable: true, symbol: '展开全文' }}>
                  {reference.content}
                </Typography.Paragraph>
                {reference.metadata?.redacted_child_count !== undefined && (
                  <Typography.Text type="secondary">
                    已剔除 {reference.metadata.redacted_child_count} 段被禁用内容
                  </Typography.Text>
                )}
                <Typography.Text className="chat-reference-id" type="secondary">
                  {reference.chunk_id}
                </Typography.Text>
              </Card>
            ))
          )}
        </aside>
      </div>
    </div>
  );
}

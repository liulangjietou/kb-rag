// Author: owlzhangfq@gmail.com
import { useEffect, useRef, useState } from 'react';
import { PauseOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Input, Select, Space, Switch, Tag, Typography } from 'antd';
import { chatPreview, listApps, streamChatPreview } from '../../api/app';
import { listKnowledgeBases } from '../../api/kb';
import ImagePicker, { toImagesPayload, type PickedImage } from '../../components/ImagePicker';
import PageHeader from '../../components/PageHeader';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import type { ChatMessage, KbApp, KnowledgeBase, RetrievalNode } from '../../api/types';
import { kbNameOf } from '../../utils/kbRefs';
import { describeDegradedReason } from '../../utils/statusMeta';

interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
  references?: RetrievalNode[];
  degraded?: string[];
  routedKbIds?: string[];
  requestId?: string;
  error?: { code: string; message: string };
  stopped?: boolean;
}

/** 问答工作台按轮次保存回答与引用，取消或切换应用后旧请求不能继续写入新会话。 */
export default function ChatDebugPage() {
  const { can } = useAuth();
  const canReadKb = can(PERMISSIONS.KB_READ);
  const [apps, setApps] = useState<KbApp[]>([]);
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
  const [referenceTurn, setReferenceTurn] = useState<number | null>(null);

  useEffect(() => {
    listApps().then((list) => {
      setApps(list);
      setAppId((prev) => prev ?? list[0]?.app_id ?? null);
    });
    if (canReadKb) listKnowledgeBases().then(setKbs);
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
    requestSequence.current += 1;
    requestRef.current?.abort();
    requestRef.current = null;
    setSending(false);
    setTurns((previous) =>
      previous.map((turn, index) =>
        index === previous.length - 1 && turn.role === 'assistant' ? { ...turn, stopped: true } : turn,
      ),
    );
  };

  const newConversation = () => {
    stop();
    setTurns([]);
    setReferenceTurn(null);
  };

  const handleSend = async () => {
    if (!appId || !input.trim() || requestRef.current) return;
    const controller = new AbortController();
    requestRef.current = controller;
    const sequence = ++requestSequence.current;
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
          { query, messages: history, app_version: appVersion || undefined, images: imagesPayload },
          {
            onDelta: (delta) => updateAnswer((turn) => ({ ...turn, content: turn.content + delta })),
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
          app_version: appVersion || undefined,
          images: imagesPayload,
        });
        updateAnswer((turn) => ({
          ...turn,
          content: response.answer,
          references: response.references,
          degraded: response.degraded,
          routedKbIds: response.routed_kb_ids,
          requestId: response.request_id,
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
        requestRef.current = null;
        setSending(false);
      }
    }
  };

  const activeReferences = referenceTurn === null ? [] : (turns[referenceTurn]?.references ?? []);

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
            value={appId ?? undefined}
            options={apps.map((app) => ({ label: app.name, value: app.app_id }))}
            onChange={(value) => {
              setAppId(value);
              newConversation();
            }}
          />
          <Input
            className="chat-version-input"
            placeholder="app_version（留空=当前正式版）"
            value={appVersion}
            disabled={sending}
            aria-label="应用版本"
            onChange={(e) => setAppVersion(e.target.value)}
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
      </Card>

      <div className="chat-workspace-grid">
        <section className="chat-main-pane" aria-label="调试对话">
          {!appId ? (
            <Alert
              className="chat-prerequisite"
              type="info"
              showIcon
              message="请先在「应用中心」创建应用后再使用问答调试"
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
                    </div>
                  ))}
                  <div ref={listEndRef} />
                </Space>
              )}
            </Card>
          )}

          <div className="chat-image-picker">
            <ImagePicker value={images} onChange={setImages} disabled={!appId} />
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
              disabled={!appId}
            />
            <Button
              className="chat-send-button"
              type="primary"
              icon={<SendOutlined />}
              loading={sending}
              disabled={!appId || !input.trim() || sending}
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

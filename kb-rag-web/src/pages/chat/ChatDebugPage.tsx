// Author: owlzhangfq@gmail.com
import { useEffect, useRef, useState } from 'react';
import { SendOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Input, Select, Space, Switch, Tag, Typography, message } from 'antd';
import { chatPreview, listApps, streamChatPreview } from '../../api/app';
import { listKnowledgeBases } from '../../api/kb';
import ImagePicker, { toImagesPayload, type PickedImage } from '../../components/ImagePicker';
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
}

/**
 * 知识问答调试页 (M4c-CONTRACTS.md section 4: "问答调试页（既有占位）接入管理端内部 chat 预览，
 * 可直接复用对外 chat 逻辑经管理鉴权路径"). No such page exists yet anywhere in this codebase (see
 * this task's reported assumptions) -- the master UI list (知识库需求文档.md section 7 item 6:
 * "知识问答调试页（对话式，含引用展示与降级标记）") describes it as its own top-level page distinct
 * from 检索调试 (item 5) and 应用中心 (item 8), so it is added here as a new top-level menu entry
 * rather than folded into either of those.
 */
export default function ChatDebugPage() {
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

  useEffect(() => {
    listApps().then((list) => {
      setApps(list);
      setAppId((prev) => prev ?? list[0]?.app_id ?? null);
    });
    listKnowledgeBases().then(setKbs);
  }, []);

  useEffect(() => {
    listEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [turns]);

  const handleSend = async () => {
    if (!appId || !input.trim()) {
      return;
    }
    const query = input.trim();
    const history: ChatMessage[] = turns.map((t) => ({ role: t.role, content: t.content }));
    const imagesPayload = toImagesPayload(images);
    setTurns((prev) => [...prev, { role: 'user', content: query }]);
    setInput('');
    setImages([]);
    setSending(true);
    try {
      if (streamEnabled) {
        let assistantIndex = -1;
        setTurns((prev) => {
          assistantIndex = prev.length;
          return [...prev, { role: 'assistant', content: '' }];
        });
        await streamChatPreview(
          appId,
          { query, messages: history, app_version: appVersion || undefined, images: imagesPayload },
          {
            onDelta: (delta) =>
              setTurns((prev) => {
                const next = [...prev];
                next[assistantIndex] = { ...next[assistantIndex], content: next[assistantIndex].content + delta };
                return next;
              }),
            onReferences: (references) =>
              setTurns((prev) => {
                const next = [...prev];
                next[assistantIndex] = { ...next[assistantIndex], references };
                return next;
              }),
            onDone: (requestId, degraded, routedKbIds) =>
              setTurns((prev) => {
                const next = [...prev];
                next[assistantIndex] = { ...next[assistantIndex], requestId, degraded, routedKbIds };
                return next;
              }),
            onError: (error) =>
              setTurns((prev) => {
                const next = [...prev];
                next[assistantIndex] = { ...next[assistantIndex], error };
                return next;
              }),
          },
        );
      } else {
        const response = await chatPreview(appId, {
          query,
          messages: history,
          app_version: appVersion || undefined,
          images: imagesPayload,
        });
        setTurns((prev) => [
          ...prev,
          {
            role: 'assistant',
            content: response.answer,
            references: response.references,
            degraded: response.degraded,
            routedKbIds: response.routed_kb_ids,
            requestId: response.request_id,
          },
        ]);
      }
    } catch {
      message.error('请求失败，请检查应用配置或稍后重试');
    } finally {
      setSending(false);
    }
  };

  return (
    <div>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>
          问答调试
        </Typography.Title>
        <Space wrap>
          <Select
            style={{ width: 220 }}
            placeholder="请选择应用"
            value={appId ?? undefined}
            options={apps.map((app) => ({ label: app.name, value: app.app_id }))}
            onChange={(value) => {
              setAppId(value);
              setTurns([]);
            }}
          />
          <Input
            style={{ width: 160 }}
            placeholder="app_version（留空=当前正式版）"
            value={appVersion}
            onChange={(e) => setAppVersion(e.target.value)}
          />
          <Space>
            <Typography.Text>流式</Typography.Text>
            <Switch checked={streamEnabled} onChange={setStreamEnabled} />
          </Space>
        </Space>
      </Space>

      {!appId ? (
        <Alert type="info" showIcon message="请先在「应用中心」创建应用后再使用问答调试" />
      ) : (
        <Card style={{ minHeight: 420, marginBottom: 16 }}>
          {turns.length === 0 ? (
            <Empty description="输入问题开始调试对话（内部走管理鉴权，复用对外 chat 生成逻辑）" />
          ) : (
            <Space direction="vertical" style={{ width: '100%' }} size={16}>
              {turns.map((turn, index) => (
                <div key={index} style={{ textAlign: turn.role === 'user' ? 'right' : 'left' }}>
                  <Tag color={turn.role === 'user' ? 'blue' : 'default'}>{turn.role === 'user' ? '我' : '助手'}</Tag>
                  <div
                    style={{
                      display: 'inline-block',
                      maxWidth: '80%',
                      textAlign: 'left',
                      background: turn.role === 'user' ? '#e6f4ff' : '#f5f5f5',
                      borderRadius: 8,
                      padding: '8px 12px',
                      whiteSpace: 'pre-wrap',
                    }}
                  >
                    {turn.content || (turn.error ? '' : '生成中...')}
                  </div>
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
                    <Space direction="vertical" style={{ width: '100%', marginTop: 8 }}>
                      {turn.references.map((ref) => (
                        <Card
                          key={ref.chunk_id}
                          size="small"
                          title={
                            <Space wrap>
                              <span>{ref.doc_id}</span>
                              {ref.metadata?.kb_id && <Tag color="purple">{kbNameOf(kbs, ref.metadata.kb_id)}</Tag>}
                            </Space>
                          }
                        >
                          <Typography.Paragraph ellipsis={{ rows: 2, expandable: true, symbol: '展开' }} style={{ marginBottom: 0 }}>
                            {ref.content}
                          </Typography.Paragraph>
                          {/* M9-CONTRACTS.md section 0.3: parent/child precise-redaction hint, see RetrievalNodeCard's identical convention. */}
                          {ref.metadata?.redacted_child_count !== undefined && (
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              已剔除 {ref.metadata.redacted_child_count} 段被禁用内容
                            </Typography.Text>
                          )}
                        </Card>
                      ))}
                    </Space>
                  )}
                  {turn.requestId && (
                    <Typography.Text type="secondary" style={{ display: 'block', marginTop: 4, fontSize: 12 }}>
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

      <div style={{ marginBottom: 8 }}>
        <ImagePicker value={images} onChange={setImages} disabled={!appId} />
      </div>
      <Space.Compact style={{ width: '100%' }}>
        <Input.TextArea
          rows={2}
          placeholder="输入问题，回车发送（Shift+回车换行）"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          disabled={!appId}
        />
        <Button type="primary" icon={<SendOutlined />} loading={sending} disabled={!appId} onClick={handleSend}>
          发送
        </Button>
      </Space.Compact>
    </div>
  );
}

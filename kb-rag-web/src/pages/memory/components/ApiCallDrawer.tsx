// Author: owlzhangfq@gmail.com
import { useMemo } from 'react';
import { CopyOutlined } from '@ant-design/icons';
import { Alert, Button, Collapse, Drawer, Space, Tag, Typography, message } from 'antd';
import type { MemoryLibraryDetail } from '../../../api/types';

interface Props {
  open: boolean;
  onClose: () => void;
  detail: MemoryLibraryDetail;
}

/** The placeholder agents must substitute with their issued kb-mk-* plaintext. */
const KEY_PLACEHOLDER = '$MEMORY_KEY';

/** Per-line inline annotations, mirroring the console convention of the reference product. */
interface LineTag {
  /** Substring that marks the line to annotate. */
  match: string;
  text: string;
  color: string;
}

interface Snippet {
  key: string;
  title: string;
  method: string;
  path: string;
  code: string;
  tags: LineTag[];
  response?: string;
}

/**
 * One copyable code block: header bar with title and copy icon, then the code line by line.
 * The $MEMORY_KEY token is highlighted and annotated lines get a trailing Tag; copying always
 * yields the raw text so the snippet stays paste-ready.
 */
function CodeBlock({ title, code, tags }: { title: string; code: string; tags: LineTag[] }) {
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      message.success('已复制到剪贴板');
    } catch {
      message.error('复制失败，请手动选择复制');
    }
  };

  const renderLine = (line: string, index: number) => {
    const tag = tags.find((t) => line.includes(t.match));
    const parts = line.split(KEY_PLACEHOLDER);
    return (
      <div key={index} style={{ whiteSpace: 'pre' }}>
        {parts.map((part, i) => (
          <span key={i}>
            {i > 0 && <span style={{ color: '#eb2f96' }}>{KEY_PLACEHOLDER}</span>}
            {part}
          </span>
        ))}
        {tag && (
          <Tag color={tag.color} style={{ marginLeft: 8, fontSize: 11, lineHeight: '16px' }}>
            {tag.text}
          </Tag>
        )}
      </div>
    );
  };

  return (
    <div style={{ border: '1px solid #f0f0f0', borderRadius: 6, marginBottom: 16 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '4px 12px',
          borderBottom: '1px solid #f0f0f0',
          background: '#fafafa',
          borderRadius: '6px 6px 0 0',
        }}
      >
        <Typography.Text strong style={{ fontSize: 13 }}>
          {title}
        </Typography.Text>
        <Button type="text" size="small" icon={<CopyOutlined />} onClick={handleCopy} />
      </div>
      <pre style={{ margin: 0, padding: 12, fontSize: 12, overflowX: 'auto', background: '#fff', borderRadius: '0 0 6px 6px' }}>
        {code.split('\n').map(renderLine)}
      </pre>
    </div>
  );
}

/**
 * API call drawer: ready-to-paste curl examples for every open API operation
 * (/api/v1/memory/**), pre-filled with this library's real rule ids and the current deploy
 * origin, so an agent developer only substitutes $MEMORY_KEY before firing the request.
 */
export default function ApiCallDrawer({ open, onClose, detail }: Props) {
  const snippets = useMemo<Snippet[]>(() => {
    const base = window.location.origin;
    // Prefer a user-created fragment rule for the example; the builtin one is what an omitted
    // fragment_rule_id falls back to anyway.
    const fragmentRule = detail.fragment_rules.find((r) => !r.builtin) ?? detail.fragment_rules[0];
    const profileRule = detail.profile_rules[0];
    const fragmentRuleId = fragmentRule?.rule_id ?? 'mfr_xxxxxxxx';
    const profileRuleId = profileRule?.rule_id ?? 'mpr_xxxxxxxx';
    const keyTag: LineTag = { match: KEY_PLACEHOLDER, text: 'Memory Key 需要您填入', color: 'magenta' };
    const fragmentTag: LineTag = {
      match: '"fragment_rule_id"',
      text: fragmentRule ? '非必填，记忆片段规则 ID' : '非必填，库内暂无规则（示例值）',
      color: 'cyan',
    };
    const profileTag: LineTag = {
      match: '"profile_rule_id"',
      text: profileRule ? '非必填，用户画像规则 ID' : '非必填，库内暂无规则（示例值）',
      color: 'cyan',
    };

    return [
      {
        key: 'add',
        title: '添加记忆（对话抽取）',
        method: 'POST',
        path: '/api/v1/memory/add',
        code: [
          `curl -X POST "${base}/api/v1/memory/add" \\`,
          `  --header "Authorization: Bearer ${KEY_PLACEHOLDER}" \\`,
          `  --header "Content-Type: application/json" \\`,
          `  --data '{`,
          `    "user_id": "user_001",`,
          `    "messages": [{`,
          `      "role": "user",`,
          `      "content": "每天上午11点提醒我点外卖。"`,
          `    }, {`,
          `      "role": "assistant",`,
          `      "content": "没问题"`,
          `    }, {`,
          `      "role": "user",`,
          `      "content": "明天10点提醒我整理会议纪要。"`,
          `    }],`,
          `    "fragment_rule_id": "${fragmentRuleId}",`,
          `    "profile_rule_id": "${profileRuleId}",`,
          `    "meta_data": {`,
          `      "location_name": "北京"`,
          `    }`,
          `  }'`,
        ].join('\n'),
        tags: [keyTag, fragmentTag, profileTag],
        response: JSON.stringify(
          {
            code: 'OK',
            message: 'success',
            data: {
              memory_nodes: [
                { memory_node_id: 'mnode_xxx', content: '用户要求每天上午11点提醒他点外卖', event: 'ADD' },
                { memory_node_id: 'mnode_yyy', content: '用户要求明天10点提醒他整理会议纪要', event: 'ADD' },
              ],
              profile: null,
            },
            request_id: 'bec69131-627c-4636-a2ff-e71c0c8a5c53',
          },
          null,
          2,
        ),
      },
      {
        key: 'add-custom',
        title: '添加记忆（自定义内容直写）',
        method: 'POST',
        path: '/api/v1/memory/add',
        code: [
          `curl -X POST "${base}/api/v1/memory/add" \\`,
          `  --header "Authorization: Bearer ${KEY_PLACEHOLDER}" \\`,
          `  --header "Content-Type: application/json" \\`,
          `  --data '{`,
          `    "user_id": "user_001",`,
          `    "custom_content": "用户周末去上海参加WAIC",`,
          `    "fragment_rule_id": "${fragmentRuleId}",`,
          `    "meta_data": {`,
          `      "custom_key": "custom_value"`,
          `    }`,
          `  }'`,
        ].join('\n'),
        tags: [keyTag, fragmentTag],
        response: JSON.stringify(
          {
            code: 'OK',
            message: 'success',
            data: {
              memory_nodes: [
                { memory_node_id: 'mnode_zzz', content: '用户周末去上海参加WAIC', event: 'ADD' },
              ],
              profile: null,
            },
            request_id: '…',
          },
          null,
          2,
        ),
      },
      {
        key: 'search',
        title: '检索记忆',
        method: 'POST',
        path: '/api/v1/memory/search',
        code: [
          `curl -X POST "${base}/api/v1/memory/search" \\`,
          `  --header "Authorization: Bearer ${KEY_PLACEHOLDER}" \\`,
          `  --header "Content-Type: application/json" \\`,
          `  --data '{`,
          `    "user_id": "user_001",`,
          `    "query": "我让你提醒我什么来着？",`,
          `    "fragment_rule_id": "${fragmentRuleId}",`,
          `    "max_results": 5,`,
          `    "intent_recognition": true,`,
          `    "rewrite": true,`,
          `    "rerank": true,`,
          `    "similarity_threshold": 0.3`,
          `  }'`,
        ].join('\n'),
        tags: [keyTag, fragmentTag],
        response: JSON.stringify(
          {
            code: 'OK',
            message: 'success',
            data: {
              memory_nodes: [
                {
                  memory_node_id: 'mnode_xxx',
                  user_id: 'user_001',
                  content: '用户要求每天上午11点提醒他点外卖',
                  source: 'EXTRACTED',
                  score: 0.87,
                  meta_data: { location_name: '北京' },
                  expire_at: null,
                  created_at: '2026-07-31 10:00:00',
                  updated_at: '2026-07-31 10:00:00',
                },
              ],
              profiles: [],
              rewritten_query: '用户设置的提醒事项',
              intent_recalled: true,
            },
            request_id: '…',
          },
          null,
          2,
        ),
      },
      {
        key: 'list',
        title: '列出记忆（分页）',
        method: 'GET',
        path: '/api/v1/memory/memory_nodes',
        code: [
          `curl -X GET "${base}/api/v1/memory/memory_nodes?user_id=user_001&page_num=1&page_size=10" \\`,
          `  --header "Authorization: Bearer ${KEY_PLACEHOLDER}"`,
        ].join('\n'),
        tags: [keyTag],
        response: JSON.stringify(
          {
            code: 'OK',
            message: 'success',
            data: { memory_nodes: ['…'], page: 1, size: 10, total: 42 },
            request_id: '…',
          },
          null,
          2,
        ),
      },
      {
        key: 'update',
        title: '更新记忆',
        method: 'PATCH',
        path: '/api/v1/memory/memory_nodes/{memory_node_id}',
        code: [
          `curl -X PATCH "${base}/api/v1/memory/memory_nodes/mnode_xxx" \\`,
          `  --header "Authorization: Bearer ${KEY_PLACEHOLDER}" \\`,
          `  --header "Content-Type: application/json" \\`,
          `  --data '{`,
          `    "user_id": "user_001",`,
          `    "custom_content": "用户要求每天上午10点半提醒他点外卖",`,
          `    "meta_data": {`,
          `      "location_name": "北京"`,
          `    }`,
          `  }'`,
        ].join('\n'),
        tags: [keyTag],
        response: JSON.stringify(
          {
            code: 'OK',
            message: 'success',
            data: {
              memory_node_id: 'mnode_xxx',
              user_id: 'user_001',
              content: '用户要求每天上午10点半提醒他点外卖',
              source: 'CUSTOM',
            },
            request_id: '…',
          },
          null,
          2,
        ),
      },
      {
        key: 'delete',
        title: '删除记忆',
        method: 'DELETE',
        path: '/api/v1/memory/memory_nodes/{memory_node_id}',
        code: [
          `curl -X DELETE "${base}/api/v1/memory/memory_nodes/mnode_xxx?user_id=user_001" \\`,
          `  --header "Authorization: Bearer ${KEY_PLACEHOLDER}"`,
        ].join('\n'),
        tags: [keyTag],
        response: JSON.stringify(
          { code: 'OK', message: 'success', data: null, request_id: '…' },
          null,
          2,
        ),
      },
      {
        key: 'profiles',
        title: '获取用户画像',
        method: 'GET',
        path: '/api/v1/memory/profiles',
        code: [
          `curl -X GET "${base}/api/v1/memory/profiles?user_id=user_001&rule_id=${profileRuleId}" \\`,
          `  --header "Authorization: Bearer ${KEY_PLACEHOLDER}"`,
        ].join('\n'),
        tags: [keyTag],
        response: JSON.stringify(
          {
            code: 'OK',
            message: 'success',
            data: [
              {
                rule_id: profileRuleId,
                rule_name: profileRule?.name ?? '基础画像',
                user_id: 'user_001',
                attributes: [{ name: '称呼', value: '张三' }],
                updated_at: '2026-07-31 10:00:00',
              },
            ],
            request_id: '…',
          },
          null,
          2,
        ),
      },
    ];
  }, [detail]);

  return (
    <Drawer title="API 调用" width={720} open={open} onClose={onClose}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={
          <span>
            所有接口以 <Typography.Text code>Authorization: Bearer kb-mk-…</Typography.Text> 鉴权，Key 在「Memory
            Key」Tab 签发；Key 已绑定本记忆库，无需传 library_id。示例中的规则 ID 已替换为本库真实值。
          </span>
        }
      />
      <Collapse
        defaultActiveKey={['add']}
        items={snippets.map((snippet) => ({
          key: snippet.key,
          label: (
            <Space>
              <Tag color="blue">{snippet.method}</Tag>
              <Typography.Text strong>{snippet.title}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {snippet.path}
              </Typography.Text>
            </Space>
          ),
          children: (
            <div>
              <CodeBlock title="Curl" code={snippet.code} tags={snippet.tags} />
              {snippet.response && (
                <>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
                    出入参示例
                  </Typography.Paragraph>
                  <CodeBlock title="Response" code={snippet.response} tags={[]} />
                </>
              )}
            </div>
          ),
        }))}
      />
    </Drawer>
  );
}

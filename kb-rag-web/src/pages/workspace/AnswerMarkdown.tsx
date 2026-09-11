import { CheckOutlined, CopyOutlined } from '@ant-design/icons';
import { Children, isValidElement, memo, useEffect, useMemo, useState, type ReactNode } from 'react';
import Markdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface MarkdownNode {
  type: string;
  value?: string;
  children?: MarkdownNode[];
  url?: string;
}

/** 只把正文里的有效本轮编号转换为引用控件，代码、现有链接和历史来源编号不参与。 */
function citationPlugin({ count }: { count: number }) {
  const visit = (node: MarkdownNode) => {
    if (!node.children || ['code', 'inlineCode', 'link', 'linkReference', 'definition', 'html'].includes(node.type)) return;
    node.children = node.children.flatMap((child) => {
      if (child.type !== 'text' || !child.value) {
        visit(child);
        return [child];
      }
      const output: MarkdownNode[] = [];
      let position = 0;
      for (const match of child.value.matchAll(/\[(\d{1,3})\]/g)) {
        const number = Number(match[1]);
        if (number < 1 || number > count) continue;
        if (match.index > position) output.push({ type: 'text', value: child.value.slice(position, match.index) });
        output.push({ type: 'link', url: `#kb-citation-${number}`, children: [{ type: 'text', value: String(number) }] });
        position = match.index + match[0].length;
      }
      if (position === 0) return [child];
      if (position < child.value.length) output.push({ type: 'text', value: child.value.slice(position) });
      return output;
    });
  };
  return visit;
}

function textContent(children: ReactNode): string {
  return Children.toArray(children).map((child) => {
    if (typeof child === 'string' || typeof child === 'number') return String(child);
    return isValidElement<{ children?: ReactNode }>(child) ? textContent(child.props.children) : '';
  }).join('');
}

/** 复制失败有明确反馈，不把失败伪装成已复制。 */
export function CopyTextButton({ text, label }: { text: string; label: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle');
  useEffect(() => {
    if (state === 'idle') return;
    const timer = window.setTimeout(() => setState('idle'), 2_500);
    return () => window.clearTimeout(timer);
  }, [state]);
  return <span className="answer-copy">
    <button type="button" className="workspace-text-action" aria-label={label} onClick={async () => {
      try { await navigator.clipboard.writeText(text); setState('copied'); }
      catch { setState('failed'); }
    }}>
      {state === 'copied' ? <CheckOutlined /> : <CopyOutlined />} {state === 'copied' ? '已复制' : label}
    </button>
    <span className={state === 'failed' ? 'answer-copy__failure' : 'workspace-sr-only'} role="status">
      {state === 'failed' ? '复制失败，请选择正文复制' : state === 'copied' ? '已复制到剪贴板' : ''}
    </span>
  </span>;
}

function CodeBlock({ children }: { children?: ReactNode }) {
  const text = textContent(children).replace(/\n$/, '');
  return <div className="answer-code"><div className="answer-code__bar"><span>代码</span>
    <CopyTextButton text={text} label="复制代码" /></div><pre tabIndex={0} aria-label="代码内容">{children}</pre></div>;
}

function safeUrl(url: string): string {
  if (/^#kb-citation-\d+$/.test(url)) return url;
  try {
    const parsed = new URL(url);
    return ['https:', 'http:', 'mailto:'].includes(parsed.protocol) ? url : '';
  } catch { return ''; }
}

/** 不执行原始 HTML、不加载远程图片；只显示经过当前授权的回答文本与引用控件。 */
const AnswerMarkdown = memo(function AnswerMarkdown({ content, citationCount, onCitation }: {
  content: string;
  citationCount: number;
  onCitation: (index: number) => void;
}) {
  const components = useMemo<Components>(() => ({
    pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,
    table: ({ children }) => <div className="answer-table" tabIndex={0} role="region" aria-label="回答表格"><table>{children}</table></div>,
    img: ({ alt }) => <span className="answer-image-note">{alt ? `图片说明：${alt}` : '图片内容未加载'}</span>,
    a: ({ href, children }) => {
      const citation = /^#kb-citation-(\d+)$/.exec(href ?? '');
      if (citation && Number(citation[1]) <= citationCount) {
        const number = Number(citation[1]);
        return <button type="button" className="answer-citation" aria-label={`查看引用 ${number}`}
          onClick={() => onCitation(number - 1)}>{number}</button>;
      }
      if (!href || !safeUrl(href) || href.startsWith('#')) return <span>{children}</span>;
      return <a href={href} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{children}</a>;
    },
  }), [citationCount, onCitation]);
  const rendered = useMemo(() => <Markdown skipHtml urlTransform={safeUrl}
    remarkPlugins={[remarkGfm, [citationPlugin, { count: citationCount }]]} components={components}>{content}</Markdown>,
  [content, citationCount, components]);
  return <div className="answer-markdown">{rendered}</div>;
});

export default AnswerMarkdown;

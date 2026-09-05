import { ArrowRightOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Empty, Input, Modal } from 'antd';
import type { InputRef } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { NAV_SECTIONS, type NavEntry } from './navigation';

/** 页面快捷跳转只接收已授权导航，不请求业务资源或扩大权限范围。 */
export default function PageCommandPalette({ entries, compact }: { entries: NavEntry[]; compact: boolean }) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const inputRef = useRef<InputRef>(null);
  const resultsRef = useRef<HTMLDivElement>(null);
  const keyword = query.trim().toLocaleLowerCase();
  const matches = entries.filter((entry) =>
    `${entry.label} ${entry.key}`.toLocaleLowerCase().includes(keyword),
  );

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setOpen((current) => !current);
      }
    };
    window.addEventListener('keydown', handleShortcut);
    return () => window.removeEventListener('keydown', handleShortcut);
  }, []);

  const visit = (path: string) => {
    setOpen(false);
    navigate(path);
  };

  return (
    <>
      <Button
        className="page-command-trigger"
        type="text"
        icon={<SearchOutlined />}
        aria-label="搜索页面与功能"
        onClick={() => setOpen(true)}
      >
        {!compact && (
          <>
            <span>搜索页面与功能</span>
            <kbd>⌘ K</kbd>
          </>
        )}
      </Button>
      <Modal
        title="搜索页面与功能"
        open={open}
        footer={null}
        width={560}
        onCancel={() => setOpen(false)}
        afterOpenChange={(visible) => {
          if (visible) inputRef.current?.focus();
          else setQuery('');
        }}
      >
        <Input
          ref={inputRef}
          prefix={<SearchOutlined />}
          value={query}
          allowClear
          aria-label="页面名称"
          placeholder="输入页面名称，如知识库、用户、评测"
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && matches[0]) visit(matches[0].key);
            if (event.key === 'ArrowDown') {
              event.preventDefault();
              resultsRef.current?.querySelector('button')?.focus();
            }
          }}
        />
        <div
          ref={resultsRef}
          className="page-command-results"
          onKeyDown={(event) => {
            if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
            const buttons = Array.from(resultsRef.current?.querySelectorAll('button') ?? []);
            const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
            event.preventDefault();
            const next = event.key === 'ArrowDown' ? index + 1 : index - 1;
            if (next < 0) inputRef.current?.focus();
            else buttons[next % buttons.length]?.focus();
          }}
        >
          {matches.map((entry) => (
            <button type="button" key={entry.key} onClick={() => visit(entry.key)}>
              {entry.icon}
              <span>
                {entry.label}
                <small>{NAV_SECTIONS.find((section) => section.key === entry.section)?.label}</small>
              </span>
              <ArrowRightOutlined />
            </button>
          ))}
          {matches.length === 0 && (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的可访问页面" />
          )}
        </div>
        <p className="page-command-help">↑ ↓ 选择 · Enter 打开 · Esc 关闭</p>
      </Modal>
    </>
  );
}

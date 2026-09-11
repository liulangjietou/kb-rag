// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AnswerMarkdown, { CopyTextButton } from './AnswerMarkdown';

afterEach(cleanup);

describe('员工安全回答', () => {
  it('解析表格和代码，但不执行 HTML 或加载模型提供的远程图片', () => {
    const { container } = render(<AnswerMarkdown citationCount={1} onCitation={vi.fn()} content={
      '| 名称 | 数值 |\n| --- | --- |\n| 项目 | 1 |\n\n```html\n<img src=x onerror=alert(1)>\n```\n\n'
      + '<script>alert(1)</script>\n\n![示意图](https://tracking.example/pixel)\n\n[危险链接](javascript:alert(1))'
    } />);
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByLabelText('代码内容').textContent).toContain('<img src=x onerror=alert(1)>');
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('a[href^="javascript:"]')).toBeNull();
    expect(screen.getByText('图片说明：示意图')).toBeTruthy();
  });

  it('仅正文中存在的本轮引用编号可点击，代码和普通链接保持原内容', () => {
    const open = vi.fn();
    render(<AnswerMarkdown citationCount={2} onCitation={open}
      content={'依据 [1] 和 [2]，未知 [9]。代码 `[1]`，外部 [1](https://example.com)。'} />);
    fireEvent.click(screen.getByRole('button', { name: '查看引用 2' }));
    expect(open).toHaveBeenCalledWith(1);
    expect(screen.queryByRole('button', { name: '查看引用 9' })).toBeNull();
    expect(screen.getAllByRole('button', { name: '查看引用 1' })).toHaveLength(1);
    expect(screen.getByRole('link').getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('复制代码保持原始内容并显示成功结果', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    render(<AnswerMarkdown citationCount={0} onCitation={vi.fn()} content={'```java\nString name = "员工";\n```'} />);
    fireEvent.click(screen.getByRole('button', { name: '复制代码' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('String name = "员工";'));
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('已复制到剪贴板'));
  });

  it('剪贴板被拒绝时给出明确失败反馈', async () => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
    render(<CopyTextButton text="正文" label="复制回答" />);
    fireEvent.click(screen.getByRole('button', { name: '复制回答' }));
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('复制失败'));
  });
});

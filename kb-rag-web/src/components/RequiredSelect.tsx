import { forwardRef } from 'react';
import { Select, type SelectProps } from 'antd';
import type { BaseSelectRef } from 'rc-select';

/**
 * rc-select 14 会把 aria-required 同时复制到外层 div 与输入框，产生无效语义。
 * 在必填选择框中通过可访问名称告知必填性，保留 Form 的校验、标签关联和焦点引用。
 */
const RequiredSelect = forwardRef<BaseSelectRef, SelectProps & { 'aria-label': string }>(
  function RequiredSelect({ 'aria-required': required, 'aria-label': label, ...props }, ref) {
    return <Select {...props} ref={ref} aria-label={required ? `${label}（必填）` : label} />;
  },
);

export default RequiredSelect;

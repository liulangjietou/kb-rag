import { useRef, useState } from 'react';
import { Button, DatePicker, Form, Grid, Input, Select, Space } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import type { ListDocumentsParams } from '../../../api/document';
import { PUBLISH_STATUS_META } from '../../../api/types';
import { PROCESS_STATUS_META } from '../../../utils/statusMeta';

export type DocumentFilters = Omit<ListDocumentsParams, 'page' | 'size'>;
type FilterValues = Omit<DocumentFilters, 'updated_from' | 'updated_to'> & { updated?: [Dayjs, Dayjs] };

/** 明确提交一次筛选；输入与已应用条件分开，中文组词不触发请求。 */
export default function DocumentFilterBar({ onApply, onRefresh, refreshing }: {
  onApply: (filters: DocumentFilters) => void;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const [form] = Form.useForm<FilterValues>();
  const composing = useRef(false);
  const screens = Grid.useBreakpoint();
  const [expanded, setExpanded] = useState(false);
  const [additionalCount, setAdditionalCount] = useState(0);
  return (
    <Form<FilterValues>
      form={form}
      layout="vertical"
      className="document-filter-form"
      aria-label="文档筛选"
      onValuesChange={(_, values) => setAdditionalCount(
        [values.process_status, values.publish_status, values.source, values.updated].filter(Boolean).length,
      )}
      onKeyDown={(event) => {
        if (event.key === 'Enter' && (composing.current || event.nativeEvent.isComposing)) event.preventDefault();
      }}
      onFinish={(values) => {
        if (composing.current) return;
        const { updated, ...filters } = values;
        onApply({
          ...filters,
          keyword: values.keyword?.trim() || undefined,
          updated_from: updated?.[0]?.startOf('day').format('YYYY-MM-DDTHH:mm:ss'),
          updated_to: updated?.[1]?.endOf('day').format('YYYY-MM-DDTHH:mm:ss.SSS'),
        });
      }}
    >
      <div className="document-filter-fields">
        <Form.Item name="keyword" label="文档名称" className="document-filter-keyword">
          <Input
            aria-label="文档名称" placeholder="搜索文件名" allowClear maxLength={200}
            onCompositionStart={() => { composing.current = true; }}
            onCompositionEnd={() => { composing.current = false; }}
          />
        </Form.Item>
        <div className="document-filter-advanced" hidden={screens.md === false && !expanded}>
          <Form.Item name="process_status" label="处理状态">
            <Select aria-label="处理状态筛选" placeholder="全部状态" allowClear options={
              Object.entries(PROCESS_STATUS_META).map(([value, meta]) => ({ value, label: meta.label }))
            } />
          </Form.Item>
          <Form.Item name="publish_status" label="发布状态">
            <Select aria-label="发布状态筛选" placeholder="全部状态" allowClear options={
              Object.entries(PUBLISH_STATUS_META).map(([value, meta]) => ({ value, label: meta.label }))
            } />
          </Form.Item>
          <Form.Item name="source" label="文档来源" className="document-filter-source">
            <Select aria-label="文档来源筛选" placeholder="全部来源" allowClear options={[
              { value: 'UPLOAD', label: '上传或未关联来源' },
              { value: 'WEB', label: '网页导入' },
              { value: 'EXTERNAL', label: '外部数据源' },
              { value: 'CHAT', label: '聊天导入' },
            ]} />
          </Form.Item>
          <Form.Item name="updated" label="更新时间" className="document-filter-date">
            <DatePicker.RangePicker format="YYYY-MM-DD" placeholder={['开始日期', '结束日期']} />
          </Form.Item>
        </div>
      </div>
      <Space wrap>
        {screens.md === false && <Button aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
          {expanded ? '收起筛选' : '更多筛选'}{additionalCount > 0 ? `（${additionalCount}）` : ''}
        </Button>}
        <Button type="primary" htmlType="submit" aria-label="筛选文档" icon={<SearchOutlined aria-hidden />}>筛选文档</Button>
        <Button onClick={() => { form.resetFields(); setAdditionalCount(0); onApply({}); }}>重置筛选</Button>
        <Button aria-label="刷新文档" icon={<ReloadOutlined aria-hidden />} loading={refreshing} onClick={onRefresh}>刷新文档</Button>
      </Space>
    </Form>
  );
}

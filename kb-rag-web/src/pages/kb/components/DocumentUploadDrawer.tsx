import { InboxOutlined } from '@ant-design/icons';
import { Button, Drawer, Empty, Space, Tag, Typography, Upload } from 'antd';
import { isAxiosError } from 'axios';
import { useEffect, useRef, useState } from 'react';
import { uploadDocument } from '../../../api/document';
import type { KbDocument } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import './document-upload.css';

interface Props { kbId: string; open: boolean; onClose: () => void; onAccepted: () => void }
interface UploadItem {
  id: string; name: string; file?: File;
  status: 'QUEUED' | 'UPLOADING' | 'RECEIVED' | 'FAILED';
  detail?: string;
  duplicated?: boolean;
}
const MAX_CONCURRENT_UPLOADS = 2;

/** 批次记录属于当前账号与知识库，关闭抽屉保留，切换身份或资源范围时卸载。 */
export default function DocumentUploadDrawer(props: Props) {
  const { token, user } = useAuth();
  return <UploadContent key={JSON.stringify([props.kbId, token, user])} {...props} />;
}

function acceptedDetail(name: string, doc: KbDocument): string {
  if (doc.duplicated) return `${name} 内容与已有版本${doc.version ? ` ${doc.version}` : ''}一致，未重复建版`;
  return doc.version ? `已生成版本 ${doc.version}，处理与审核状态请在文档列表核对。` : '文件已接收，处理与审核状态请在文档列表核对。';
}

function failureDetail(error: unknown): string {
  if (isAxiosError<{ message?: string }>(error)) {
    const detail = error.response?.data?.message;
    if (typeof detail === 'string' && detail.trim()) return detail;
    if (!error.response) return '连接中断，未能确认结果，请先到文档列表核对。';
  }
  return '上传结果未确认，请核对文件与当前权限后重试。';
}

function UploadContent({ kbId, open, onClose, onAccepted }: Props) {
  const [items, setItems] = useState<UploadItem[]>([]);
  const mounted = useRef(true);
  const active = useRef(new Map<string, string>());
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    for (const item of items) {
      if (active.current.size >= MAX_CONCURRENT_UPLOADS) break;
      if (item.status !== 'QUEUED' || !item.file || active.current.has(item.id)) continue;
      // 同名文件串行入库，保持用户选取的版本顺序。
      if ([...active.current.values()].includes(item.name)) continue;
      active.current.set(item.id, item.name);
      setItems((current) => current.map((row) => row.id === item.id ? { ...row, status: 'UPLOADING' } : row));
      void uploadDocument(kbId, item.file).then((doc) => {
        if (!mounted.current) return;
        setItems((current) => current.map((row) => row.id === item.id ? {
          ...row, file: undefined, status: 'RECEIVED', duplicated: doc.duplicated, detail: acceptedDetail(item.name, doc),
        } : row));
        onAccepted();
      }).catch((error: unknown) => {
        if (mounted.current) setItems((current) => current.map((row) => row.id === item.id
          ? { ...row, status: 'FAILED', detail: failureDetail(error) } : row));
      }).finally(() => {
        active.current.delete(item.id);
        // 空出并发槽后重新检查队列；卸载后不启动其余文件，也不刷新另一知识库。
        if (mounted.current) setItems((current) => [...current]);
      });
    }
  }, [items, kbId, onAccepted]);

  const received = items.filter((item) => item.status === 'RECEIVED').length;
  const failed = items.filter((item) => item.status === 'FAILED').length;
  const pending = items.length - received - failed;
  const retry = (id?: string) => setItems((current) => current.map((item) => item.status === 'FAILED' && (!id || item.id === id)
    ? { ...item, status: 'QUEUED', detail: undefined } : item));

  return <Drawer title="添加文档" open={open} width={620} onClose={onClose}>
    <Typography.Paragraph type="secondary">
      文件接收后自动解析，处理与审核状态可在文档列表查看。关闭抽屉可继续上传；批次记录保留在当前知识库页面。
    </Typography.Paragraph>
    <Upload.Dragger multiple showUploadList={false} className="document-upload-zone" beforeUpload={(file) => {
      setItems((current) => [...current, { id: crypto.randomUUID(), name: file.name, file, status: 'QUEUED' }]);
      return Upload.LIST_IGNORE;
    }}>
      <p className="ant-upload-drag-icon"><InboxOutlined /></p>
      <p className="ant-upload-text">点击或拖拽文件到此处上传</p>
      <p className="ant-upload-hint">支持 pdf / docx / txt / md / sql / xlsx / csv / html，单文件不超过 100MB，可批量上传</p>
    </Upload.Dragger>
    <section aria-label="本次上传记录" className="document-upload-results">
      <header><h2>本次上传</h2><span role="status">已接收 {received} · 失败或未确认 {failed} · 等待与上传中 {pending}</span></header>
      <Space wrap>
        <Button disabled={!failed} onClick={() => retry()}>仅重试失败文件</Button>
        <Button disabled={!received} onClick={() => setItems((current) => current.filter((item) => item.status !== 'RECEIVED'))}>清除已接收记录</Button>
      </Space>
      {items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择文件后，会在这里保留每个文件的结果" />
        : <ol className="document-upload-list">{items.map((item) => <li key={item.id}>
          <div className="document-upload-row"><strong>{item.name}</strong>
            <Tag color={item.status === 'RECEIVED' ? 'success' : item.status === 'FAILED' ? 'error' : 'processing'}>
              {item.status === 'QUEUED' ? '等待上传' : item.status === 'UPLOADING' ? '上传中' : item.status === 'FAILED'
                ? '失败或未确认' : item.duplicated ? '内容已存在' : '已接收'}
            </Tag>
          </div>
          {item.detail && <p>{item.detail}</p>}
          {item.status === 'FAILED' && <Space wrap>
            <Button size="small" aria-label={`重试 ${item.name}`} onClick={() => retry(item.id)}>重试</Button>
            <Button size="small" aria-label={`移除 ${item.name}`} onClick={() => setItems((current) => current.filter((row) => row.id !== item.id))}>移除</Button>
          </Space>}
        </li>)}</ol>}
    </section>
  </Drawer>;
}

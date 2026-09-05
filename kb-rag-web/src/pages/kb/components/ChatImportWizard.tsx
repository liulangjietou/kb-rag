// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { InboxOutlined } from '@ant-design/icons';
import { Alert, Button, Modal, Select, Space, Table, Tag, Typography, Upload, message } from 'antd';
import type { UploadProps } from 'antd';
import { confirmChatImport, previewChatImport } from '../../../api/chatImport';
import { listSourceMappings } from '../../../api/sourceMapping';
import type { ChatImportSessionPreview, SourceMapping } from '../../../api/types';
import { formatEpochMillis } from '../../../utils/format';
import { CHAT_IMPORT_ACTION_META, metaOf } from '../../../utils/statusMeta';

interface ChatImportWizardProps {
  kbId: string;
  open: boolean;
  onClose: () => void;
  /** Called after a successful confirm so the caller can refresh the document list. */
  onImported: () => void;
}

/**
 * Chat log import wizard (M3-CONTRACTS.md sections 3.5/4, format range extended to txt/html by
 * M8-CONTRACTS.md section 0.1-0.3/0.7): upload csv/xlsx/txt/html -> session match preview table
 * (session name / message count / time range / CREATE|NEW_VERSION action) -> confirm import.
 * Nothing is persisted until the confirm step; the preview upload_token is only valid for 30
 * minutes server-side. The mapping-profile picker reads GET /source-mappings (M8 section 0.7)
 * instead of accepting a free-text profile name, so operators only ever pick from
 * server-known/validated profiles (built-in or custom).
 */
export default function ChatImportWizard({ kbId, open, onClose, onImported }: ChatImportWizardProps) {
  const [mappingId, setMappingId] = useState<string | undefined>(undefined);
  const [mappings, setMappings] = useState<SourceMapping[]>([]);
  const [mappingsLoading, setMappingsLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadToken, setUploadToken] = useState<string | null>(null);
  const [sessions, setSessions] = useState<ChatImportSessionPreview[]>([]);
  /** Messages the parser dropped, keyed by reason (e.g. voice rows carrying no text). */
  const [skipped, setSkipped] = useState<Record<string, number>>({});
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    if (!open) return;
    setMappingsLoading(true);
    listSourceMappings()
      .then(setMappings)
      .finally(() => setMappingsLoading(false));
  }, [open]);

  const reset = () => {
    setUploadToken(null);
    setSessions([]);
    setSkipped({});
    setMappingId(undefined);
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const uploadProps: UploadProps = {
    multiple: false,
    showUploadList: false,
    disabled: uploading,
    accept: '.csv,.xlsx,.txt,.html',
    customRequest: async (options) => {
      const { file, onSuccess, onError } = options;
      setUploading(true);
      try {
        const result = await previewChatImport(kbId, file as File, mappingId);
        setUploadToken(result.upload_token);
        setSessions(result.sessions);
        setSkipped(result.skipped ?? {});
        onSuccess?.(result);
      } catch (err) {
        onError?.(err as Error);
      } finally {
        setUploading(false);
      }
    },
  };

  const handleConfirm = async () => {
    if (!uploadToken) return;
    setConfirming(true);
    try {
      await confirmChatImport(kbId, { upload_token: uploadToken });
      message.success('聊天记录导入成功');
      reset();
      onImported();
    } finally {
      setConfirming(false);
    }
  };

  return (
    <Modal
      title="导入聊天记录"
      open={open}
      onCancel={handleClose}
      width={760}
      destroyOnHidden
      footer={
        sessions.length > 0
          ? [
              <Button key="cancel" onClick={handleClose}>
                取消
              </Button>,
              <Button key="confirm" type="primary" loading={confirming} onClick={handleConfirm}>
                确认导入（{sessions.length} 个会话）
              </Button>,
            ]
          : null
      }
    >
      {sessions.length === 0 ? (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Paragraph type="secondary">
            上传聊天记录导出文件（csv/xlsx/txt/html），系统按映射档案解析会话并生成匹配预览，确认后才会写入知识库；脱敏默认开启。
          </Typography.Paragraph>
          <Select
            placeholder="映射档案（可选，留空使用格式默认内置模板）"
            allowClear
            loading={mappingsLoading}
            value={mappingId}
            onChange={setMappingId}
            style={{ width: '100%', marginBottom: 8 }}
            options={mappings.map((m) => ({
              value: m.mapping_id,
              label: (
                <Space>
                  <span>{m.name}</span>
                  {m.is_builtin && <Tag color="processing">内置</Tag>}
                </Space>
              ),
            }))}
          />
          <Upload.Dragger {...uploadProps}>
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽 csv/xlsx/txt/html 文件到此处上传</p>
          </Upload.Dragger>
        </Space>
      ) : (
        <>
          <Alert
            type="info"
            showIcon
            message="会话匹配预览"
            description="已存在同一渠道+会话标识的文档将生成新版本，新会话将新建文档；确认后才会写入知识库"
            style={{ marginBottom: 16 }}
          />
          {Object.keys(skipped).length > 0 && (
            // The preview response reports what the parser dropped (voice/video rows and the like);
            // leaving it unshown made a partial import look complete.
            <Alert
              type="warning"
              showIcon
              message="部分消息未纳入导入"
              description={
                <Space wrap>
                  {Object.entries(skipped).map(([reason, count]) => (
                    <Tag key={reason}>
                      {reason}：{count} 条
                    </Tag>
                  ))}
                </Space>
              }
              style={{ marginBottom: 16 }}
            />
          )}
          <Table<ChatImportSessionPreview>
            rowKey="session_id"
            size="small"
            dataSource={sessions}
            pagination={false}
            columns={[
              { title: '会话名', dataIndex: 'session_name' },
              { title: '消息数', dataIndex: 'message_count', width: 90 },
              {
                title: '时间范围',
                width: 320,
                render: (_, record) =>
                  `${formatEpochMillis(record.time_range.from)} ~ ${formatEpochMillis(record.time_range.to)}`,
              },
              {
                title: '动作',
                dataIndex: 'action',
                width: 120,
                render: (action: ChatImportSessionPreview['action']) => {
                  const meta = metaOf(CHAT_IMPORT_ACTION_META, action);
                  return <Tag color={meta.color}>{meta.label}</Tag>;
                },
              },
            ]}
          />
        </>
      )}
    </Modal>
  );
}

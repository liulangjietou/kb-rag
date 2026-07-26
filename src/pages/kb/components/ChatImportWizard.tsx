// Author: owlzhangfq@gmail.com
import { useState } from 'react';
import { InboxOutlined } from '@ant-design/icons';
import { Alert, Button, Input, Modal, Space, Table, Tag, Typography, Upload, message } from 'antd';
import type { UploadProps } from 'antd';
import { confirmChatImport, previewChatImport } from '../../../api/chatImport';
import type { ChatImportSessionPreview } from '../../../api/types';
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
 * Chat log import wizard (M3-CONTRACTS.md sections 3.5/4): upload csv/xlsx -> session match
 * preview table (session name / message count / time range / CREATE|NEW_VERSION action) ->
 * confirm import. Nothing is persisted until the confirm step; the preview upload_token is only
 * valid for 30 minutes server-side.
 */
export default function ChatImportWizard({ kbId, open, onClose, onImported }: ChatImportWizardProps) {
  const [mappingProfile, setMappingProfile] = useState('');
  const [uploading, setUploading] = useState(false);
  const [uploadToken, setUploadToken] = useState<string | null>(null);
  const [sessions, setSessions] = useState<ChatImportSessionPreview[]>([]);
  const [confirming, setConfirming] = useState(false);

  const reset = () => {
    setUploadToken(null);
    setSessions([]);
    setMappingProfile('');
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const uploadProps: UploadProps = {
    multiple: false,
    showUploadList: false,
    disabled: uploading,
    accept: '.csv,.xlsx',
    customRequest: async (options) => {
      const { file, onSuccess, onError } = options;
      setUploading(true);
      try {
        const result = await previewChatImport(kbId, file as File, mappingProfile || undefined);
        setUploadToken(result.upload_token);
        setSessions(result.sessions);
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
      destroyOnClose
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
            上传聊天记录导出文件（csv/xlsx），系统按映射档案解析会话并生成匹配预览，确认后才会写入知识库；脱敏默认开启。
          </Typography.Paragraph>
          <Input
            placeholder="映射档案（可选，默认 memotrace）"
            value={mappingProfile}
            onChange={(e) => setMappingProfile(e.target.value)}
            style={{ marginBottom: 8 }}
          />
          <Upload.Dragger {...uploadProps}>
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽 csv/xlsx 文件到此处上传</p>
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

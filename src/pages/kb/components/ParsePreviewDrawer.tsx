// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { Alert, Button, Card, Col, Drawer, Empty, Form, Image, Row, Space, Spin, Typography, message } from 'antd';
import { confirmDocument, getDocumentPreview, reparseDocument } from '../../../api/document';
import type { CleanRules, DocumentPreview, KbDocument } from '../../../api/types';
import CleanRulesFields from './CleanRulesFields';

const DEFAULT_CLEAN_RULES: CleanRules = {
  strip_header_footer: false,
  strip_watermark_patterns: [],
  regex_replacements: [],
  excel_header_join: true,
  extract_metadata: false,
  desensitize: { enabled: false, phone: true, id_card: true, bank_card: true, email: false },
};

interface ParsePreviewDrawerProps {
  /** The PENDING_CONFIRM document being previewed; drawer is closed when null. */
  doc: KbDocument | null;
  onClose: () => void;
  /** Called after a successful confirm so the caller can refresh the document list. */
  onConfirmed: () => void;
}

/**
 * Parse preview + confirm drawer (M3-CONTRACTS.md sections 3.4/4). Opened from a document row
 * whose process_status is PENDING_CONFIRM. The markdown body is rendered as plain preformatted
 * text -- React always escapes text children and dangerouslySetInnerHTML is never used here --
 * per the requirements doc's XSS constraint on untrusted parsed content (section 4.2).
 */
export default function ParsePreviewDrawer({ doc, onClose, onConfirmed }: ParsePreviewDrawerProps) {
  const [preview, setPreview] = useState<DocumentPreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [reparseOpen, setReparseOpen] = useState(false);
  const [reparsing, setReparsing] = useState(false);
  const [reparseForm] = Form.useForm<CleanRules>();

  useEffect(() => {
    // reparseForm is a single long-lived FormInstance owned by this component (it outlives the
    // per-document Drawer content because of destroyOnClose), so its store must be reset here --
    // otherwise unsaved edits typed for one document would leak into the next document's form.
    reparseForm.resetFields();
    if (!doc) {
      setPreview(null);
      setReparseOpen(false);
      return;
    }
    setLoading(true);
    getDocumentPreview(doc.doc_id)
      .then(setPreview)
      .finally(() => setLoading(false));
  }, [doc, reparseForm]);

  const handleConfirm = async () => {
    if (!doc) return;
    setConfirming(true);
    try {
      await confirmDocument(doc.doc_id);
      message.success('已确认入库');
      onConfirmed();
    } finally {
      setConfirming(false);
    }
  };

  const handleReparse = async () => {
    if (!doc) return;
    const values = await reparseForm.validateFields();
    setReparsing(true);
    try {
      const next = await reparseDocument(doc.doc_id, { clean_rules: values });
      setPreview(next);
      setReparseOpen(false);
      message.success('已按新规则重新解析，请核对预览结果后再确认入库');
    } finally {
      setReparsing(false);
    }
  };

  return (
    <Drawer
      title={`解析预览${doc ? ` - ${doc.file_name}` : ''}`}
      open={doc !== null}
      onClose={onClose}
      width={720}
      destroyOnClose
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={() => setReparseOpen((prev) => !prev)}>改规则重解析</Button>
          <Button type="primary" loading={confirming} onClick={handleConfirm}>
            确认入库
          </Button>
        </Space>
      }
    >
      <Spin spinning={loading}>
        {!preview && !loading && <Empty description="暂无预览数据" />}
        {preview && (
          <>
            {preview.warnings.length > 0 && (
              <Alert
                type="warning"
                showIcon
                message="解析警告"
                description={preview.warnings.join('；')}
                style={{ marginBottom: 16 }}
              />
            )}

            {reparseOpen && (
              <Card
                size="small"
                title="按新清洗规则重解析（仅本次预览生效，不修改知识库配置）"
                style={{ marginBottom: 16 }}
              >
                <Form<CleanRules> form={reparseForm} layout="vertical" initialValues={DEFAULT_CLEAN_RULES}>
                  <CleanRulesFields form={reparseForm} namePath={[]} />
                </Form>
                <Button type="primary" loading={reparsing} onClick={handleReparse}>
                  重新解析
                </Button>
              </Card>
            )}

            {preview.images.length > 0 && (
              <>
                <Typography.Title level={5}>图片（{preview.images.length}）</Typography.Title>
                <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
                  {preview.images.map((img) => (
                    <Col key={img.image_id} xs={24} sm={12} md={8}>
                      <Card size="small" styles={{ body: { padding: 8 } }}>
                        <Image src={img.preview_url} width="100%" height={140} style={{ objectFit: 'cover' }} />
                        <Typography.Paragraph
                          type="secondary"
                          style={{ marginTop: 8, marginBottom: 0, whiteSpace: 'pre-wrap' }}
                          ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}
                        >
                          {img.text_proxy}
                        </Typography.Paragraph>
                      </Card>
                    </Col>
                  ))}
                </Row>
              </>
            )}

            <Typography.Title level={5}>markdown 预览（纯文本，不做 HTML 渲染）</Typography.Title>
            <pre
              style={{
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                background: 'rgba(0,0,0,0.02)',
                padding: 12,
                borderRadius: 4,
                maxHeight: 480,
                overflow: 'auto',
              }}
            >
              {preview.markdown}
            </pre>
          </>
        )}
      </Spin>
    </Drawer>
  );
}

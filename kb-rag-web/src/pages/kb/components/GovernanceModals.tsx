// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { DatePicker, Form, Input, Modal, Typography, message } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { rejectDocument, updateDocumentValidity } from '../../../api/document';
import type { KbDocument } from '../../../api/types';

// Matches the zone-less ISO literal the governance endpoints parse server-side
// (LocalDateTime.parse); Dayjs#toISOString's trailing Z would be rejected.
const TIME_PARAM_FORMAT = 'YYYY-MM-DDTHH:mm:ss';

interface ValidityModalProps {
  /** Document being edited; doubles as the modal's open flag. */
  doc: KbDocument | null;
  onClose: () => void;
  onSaved: () => void;
}

interface ValidityFormValues {
  effective_at?: Dayjs | null;
  expires_at?: Dayjs | null;
}

/**
 * 有效期设置 modal (M11-CONTRACTS.md section 2.2): both bounds optional, null = unbounded. An
 * expires_at in the past is deliberately allowed -- it is how "take this document offline right
 * now" works -- so no disabledDate guard on either picker.
 */
export function ValidityModal({ doc, onClose, onSaved }: ValidityModalProps) {
  const [form] = Form.useForm<ValidityFormValues>();
  const [submitting, setSubmitting] = useState(false);

  // Re-seed the pickers from the row every time the modal opens for a different document.
  useEffect(() => {
    if (doc) {
      form.setFieldsValue({
        effective_at: doc.effective_at ? dayjs(doc.effective_at) : null,
        expires_at: doc.expires_at ? dayjs(doc.expires_at) : null,
      });
    }
  }, [doc, form]);

  const handleOk = async () => {
    if (!doc) return;
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await updateDocumentValidity(
        doc.doc_id,
        values.effective_at ? values.effective_at.format(TIME_PARAM_FORMAT) : null,
        values.expires_at ? values.expires_at.format(TIME_PARAM_FORMAT) : null,
      );
      message.success('有效期已更新');
      onClose();
      onSaved();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={`设置有效期${doc ? ` - ${doc.file_name}` : ''}`}
      open={doc !== null}
      onOk={handleOk}
      onCancel={onClose}
      confirmLoading={submitting}
      okText="保存"
      cancelText="取消"
      destroyOnClose
    >
      <Typography.Paragraph type="secondary">
        仅在有效期窗口内的文档参与检索。两端均可留空表示不设界；将「失效时间」设为过去可立即下架。
      </Typography.Paragraph>
      <Form<ValidityFormValues> form={form} layout="vertical">
        <Form.Item name="effective_at" label="生效时间（留空 = 立即生效）">
          <DatePicker showTime style={{ width: '100%' }} placeholder="不设生效下界" />
        </Form.Item>
        <Form.Item
          name="expires_at"
          label="失效时间（留空 = 永久有效）"
          dependencies={['effective_at']}
          rules={[
            ({ getFieldValue }) => ({
              validator(_, value: Dayjs | null | undefined) {
                const effective = getFieldValue('effective_at') as Dayjs | null | undefined;
                if (value && effective && !value.isAfter(effective)) {
                  return Promise.reject(new Error('失效时间必须晚于生效时间'));
                }
                return Promise.resolve();
              },
            }),
          ]}
        >
          <DatePicker showTime style={{ width: '100%' }} placeholder="不设失效上界" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

interface RejectModalProps {
  /** Document being rejected; doubles as the modal's open flag. */
  doc: KbDocument | null;
  onClose: () => void;
  onRejected: () => void;
}

interface RejectFormValues {
  note: string;
}

/** 驳回 modal: the note is mandatory (server rejects a blank one) and lands in review_note. */
export function RejectModal({ doc, onClose, onRejected }: RejectModalProps) {
  const [form] = Form.useForm<RejectFormValues>();
  const [submitting, setSubmitting] = useState(false);

  const handleOk = async () => {
    if (!doc) return;
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await rejectDocument(doc.doc_id, values.note.trim());
      message.success('已驳回，作者可修改后重新提交审核');
      form.resetFields();
      onClose();
      onRejected();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={`驳回文档${doc ? ` - ${doc.file_name}` : ''}`}
      open={doc !== null}
      onOk={handleOk}
      onCancel={() => {
        form.resetFields();
        onClose();
      }}
      confirmLoading={submitting}
      okText="驳回"
      okButtonProps={{ danger: true }}
      cancelText="取消"
      destroyOnClose
    >
      <Form<RejectFormValues> form={form} layout="vertical">
        <Form.Item
          name="note"
          label="驳回原因"
          rules={[{ required: true, whitespace: true, message: '请填写驳回原因' }]}
        >
          <Input.TextArea rows={4} maxLength={512} showCount placeholder="说明需要修改的内容，将展示给文档提交人" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

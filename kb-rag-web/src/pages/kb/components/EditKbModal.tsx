import { useEffect, useState } from 'react';
import { Form, Input, Modal, message } from 'antd';
import { updateKnowledgeBase } from '../../../api/kb';
import type { KnowledgeBase, UpdateKbRequest } from '../../../api/types';

interface EditKbModalProps {
  /** The base being edited; null keeps the modal closed. */
  kb: KnowledgeBase | null;
  onClose: () => void;
  onUpdated: (kb: KnowledgeBase) => void;
}

export default function EditKbModal({ kb, onClose, onUpdated }: EditKbModalProps) {
  const [form] = Form.useForm<UpdateKbRequest>();
  const [submitting, setSubmitting] = useState(false);

  // Refill on every open: the same modal instance is reused across cards, so stale
  // values from the previously edited base must not leak into the next one.
  useEffect(() => {
    if (kb) {
      form.setFieldsValue({ name: kb.name, description: kb.description ?? undefined });
    }
  }, [kb, form]);

  const handleOk = async () => {
    if (!kb) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const updated = await updateKnowledgeBase(kb.kb_id, values);
      message.success('知识库已更新');
      form.resetFields();
      onUpdated(updated);
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    form.resetFields();
    onClose();
  };

  return (
    <Modal
      title="编辑知识库"
      open={!!kb}
      onOk={handleOk}
      onCancel={handleCancel}
      confirmLoading={submitting}
      okText="保存"
      cancelText="取消"
      destroyOnClose
    >
      <Form<UpdateKbRequest> form={form} layout="vertical">
        <Form.Item
          name="name"
          label="知识库名称"
          rules={[{ required: true, message: '请输入知识库名称' }]}
        >
          <Input placeholder="例如：产品文档知识库" maxLength={64} />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea placeholder="选填，简单描述知识库用途" maxLength={256} rows={3} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

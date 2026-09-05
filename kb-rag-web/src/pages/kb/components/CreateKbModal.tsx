import { useState } from 'react';
import { Form, Input, Modal, message } from 'antd';
import { createKnowledgeBase } from '../../../api/kb';
import type { CreateKbRequest, KnowledgeBase } from '../../../api/types';

interface CreateKbModalProps {
  open: boolean;
  onClose: () => void;
  onCreated: (kb: KnowledgeBase) => void;
}

export default function CreateKbModal({ open, onClose, onCreated }: CreateKbModalProps) {
  const [form] = Form.useForm<CreateKbRequest>();
  const [submitting, setSubmitting] = useState(false);

  const handleOk = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const kb = await createKnowledgeBase(values);
      message.success('知识库创建成功');
      form.resetFields();
      onCreated(kb);
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
      title="新建知识库"
      open={open}
      onOk={handleOk}
      onCancel={handleCancel}
      confirmLoading={submitting}
      okText="创建"
      cancelText="取消"
      destroyOnHidden
    >
      <Form<CreateKbRequest> form={form} layout="vertical">
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

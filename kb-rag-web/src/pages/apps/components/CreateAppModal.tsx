// Author: owlzhangfq@gmail.com
import { useState } from 'react';
import { Form, Input, Modal, message } from 'antd';
import { createApp } from '../../../api/app';
import type { CreateAppRequest, KbApp } from '../../../api/types';

interface CreateAppModalProps {
  open: boolean;
  onClose: () => void;
  onCreated: (app: KbApp) => void;
}

/** App creation modal (M4c-CONTRACTS.md section 4: "应用中心（新顶级菜单）：应用列表/新建"). */
export default function CreateAppModal({ open, onClose, onCreated }: CreateAppModalProps) {
  const [form] = Form.useForm<CreateAppRequest>();
  const [submitting, setSubmitting] = useState(false);

  const handleOk = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const app = await createApp(values);
      message.success('应用创建成功');
      form.resetFields();
      onCreated(app);
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
      rootClassName="catalog-eval-modal"
      title="新建应用"
      open={open}
      onOk={handleOk}
      onCancel={handleCancel}
      confirmLoading={submitting}
      okText="创建"
      cancelText="取消"
      destroyOnClose
    >
      <Form<CreateAppRequest> form={form} layout="vertical">
        <Form.Item name="name" label="应用名称" rules={[{ required: true, message: '请输入应用名称' }]}>
          <Input placeholder="例如：产品客服问答应用" maxLength={64} />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea placeholder="选填，简单描述应用用途" maxLength={256} rows={3} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

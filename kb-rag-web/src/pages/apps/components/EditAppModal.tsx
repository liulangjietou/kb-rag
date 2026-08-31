// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { Form, Input, Modal, message } from 'antd';
import { updateApp } from '../../../api/app';
import type { KbApp, UpdateAppRequest } from '../../../api/types';

interface EditAppModalProps {
  /** The app being edited; null keeps the modal closed. */
  app: KbApp | null;
  onClose: () => void;
  onUpdated: (app: KbApp) => void;
}

/** App rename/description modal, sibling of CreateAppModal (AppController#update). */
export default function EditAppModal({ app, onClose, onUpdated }: EditAppModalProps) {
  const [form] = Form.useForm<UpdateAppRequest>();
  const [submitting, setSubmitting] = useState(false);

  // Refill on every open: the same modal instance is reused across cards, so stale
  // values from the previously edited app must not leak into the next one.
  useEffect(() => {
    if (app) {
      form.setFieldsValue({ name: app.name, description: app.description ?? undefined });
    }
  }, [app, form]);

  const handleOk = async () => {
    if (!app) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const updated = await updateApp(app.app_id, values);
      message.success('应用已更新');
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
      rootClassName="catalog-eval-modal"
      title="编辑应用"
      open={!!app}
      onOk={handleOk}
      onCancel={handleCancel}
      confirmLoading={submitting}
      okText="保存"
      cancelText="取消"
      destroyOnClose
    >
      <Form<UpdateAppRequest> form={form} layout="vertical">
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

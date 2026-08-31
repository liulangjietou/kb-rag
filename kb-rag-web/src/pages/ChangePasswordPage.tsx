import { LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { App as AntApp, Button, Form, Input, Typography } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { changePassword } from '../api/auth';
import { useAuth } from '../auth/AuthContext';
import AuthShell from '../components/AuthShell';

interface ChangePasswordFormValues {
  old_password: string;
  new_password: string;
  confirm_password: string;
}

/** 首次登录必须完成的密码修改流程。 */
export default function ChangePasswordPage() {
  const [submitting, setSubmitting] = useState(false);
  const { message } = AntApp.useApp();
  const { passwordChanged, logout } = useAuth();
  const navigate = useNavigate();

  const handleFinish = async (values: ChangePasswordFormValues) => {
    setSubmitting(true);
    try {
      await changePassword({ old_password: values.old_password, new_password: values.new_password });
      message.success('密码修改成功');
      passwordChanged();
      navigate('/kb', { replace: true });
    } finally {
      setSubmitting(false);
    }
  };

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <AuthShell
      eyebrow="SECURITY CHECK"
      headline="安全边界，也是知识可信的一部分。"
      description="账号、权限和数据范围共同保护企业知识。完成首次密码更新后，即可进入工作台。"
      compactCard
    >
      <div className="auth-status-icon"><SafetyCertificateOutlined /></div>
      <Typography.Title level={2}>首次登录，请设置新密码</Typography.Title>
      <Typography.Paragraph type="secondary">
        初始密码仅用于首次登录，更新后才能继续使用平台。
      </Typography.Paragraph>
      <Form<ChangePasswordFormValues>
        className="password-form"
        layout="vertical"
        onFinish={handleFinish}
        autoComplete="on"
      >
        <Form.Item name="old_password" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
          <Input.Password
            name="current-password"
            autoComplete="current-password"
            prefix={<LockOutlined />}
            placeholder="输入当前密码"
          />
        </Form.Item>
        <Form.Item
          name="new_password"
          label="新密码"
          extra="至少 8 位，请避免使用容易猜测的信息"
          rules={[
            { required: true, message: '请输入新密码' },
            { min: 8, message: '新密码长度不能少于 8 位' },
          ]}
        >
          <Input.Password
            name="new-password"
            autoComplete="new-password"
            prefix={<LockOutlined />}
            placeholder="输入新密码"
          />
        </Form.Item>
        <Form.Item
          name="confirm_password"
          label="确认新密码"
          dependencies={['new_password']}
          rules={[
            { required: true, message: '请再次输入新密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('new_password') === value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error('两次输入的新密码不一致'));
              },
            }),
          ]}
        >
          <Input.Password
            name="confirm-new-password"
            autoComplete="new-password"
            prefix={<LockOutlined />}
            placeholder="再次输入新密码"
          />
        </Form.Item>
        <Form.Item className="password-form__submit">
          <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
            更新密码并进入
          </Button>
        </Form.Item>
      </Form>
      <button className="auth-text-action" type="button" onClick={handleLogout}>退出当前账号</button>
    </AuthShell>
  );
}

import { useState } from 'react';
import { LockOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { changePassword } from '../api/auth';
import { useAuth } from '../auth/AuthContext';

interface ChangePasswordFormValues {
  old_password: string;
  new_password: string;
  confirm_password: string;
}

/**
 * Forced first-login password change flow (must_change_password=true from /auth/login),
 * see M1-CONTRACTS.md section 5.
 */
export default function ChangePasswordPage() {
  const [submitting, setSubmitting] = useState(false);
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
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>
          首次登录，请修改密码
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
          为保障账户安全，首次登录必须修改初始密码后才能继续使用
        </Typography.Paragraph>
        <Form<ChangePasswordFormValues> layout="vertical" onFinish={handleFinish} autoComplete="off">
          <Form.Item name="old_password" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="请输入原密码" />
          </Form.Item>
          <Form.Item
            name="new_password"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, message: '新密码长度不能少于 8 位' },
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="请输入新密码" />
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
            <Input.Password prefix={<LockOutlined />} placeholder="请再次输入新密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              确认修改
            </Button>
          </Form.Item>
        </Form>
        <div style={{ textAlign: 'center' }}>
          <a onClick={handleLogout}>退出登录</a>
        </div>
      </Card>
    </div>
  );
}

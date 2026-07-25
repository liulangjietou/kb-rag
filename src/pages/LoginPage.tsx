import { useState } from 'react';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, Typography } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { login } from '../api/auth';
import { useAuth } from '../auth/AuthContext';
import type { LoginRequest } from '../api/types';

interface LocationState {
  from?: { pathname: string };
}

export default function LoginPage() {
  const [submitting, setSubmitting] = useState(false);
  const { loginSuccess } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const handleFinish = async (values: LoginRequest) => {
    setSubmitting(true);
    try {
      const res = await login(values);
      loginSuccess(res.token, res.must_change_password);
      if (res.must_change_password) {
        navigate('/change-password', { replace: true });
        return;
      }
      const state = location.state as LocationState | null;
      navigate(state?.from?.pathname ?? '/kb', { replace: true });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
          知识库管理台
        </Typography.Title>
        <Form<LoginRequest> layout="vertical" onFinish={handleFinish} autoComplete="off">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="admin" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="请输入密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

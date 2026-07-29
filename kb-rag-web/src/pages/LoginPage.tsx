// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, Tabs, Typography } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { getSsoAvailability, login } from '../api/auth';
import type { LoginMode } from '../api/types';
import { useAuth } from '../auth/AuthContext';

interface LocationState {
  from?: { pathname: string };
}

interface FormValues {
  username: string;
  password: string;
}

/**
 * Two ways in, one form.
 *
 * The directory tab is offered only when the deployment actually has one wired up, which the server
 * answers before anybody is authenticated. Rendering it unconditionally would put a door on the page
 * that opens onto "域账号认证服务未配置" for every deployment that runs on local accounts alone.
 *
 * The mode travels with the credentials rather than being guessed from the username: the same person may
 * hold both a domain account and a local one, and a domain password must never be tried against the
 * local hash table or the other way round.
 */
export default function LoginPage() {
  const [submitting, setSubmitting] = useState(false);
  const [ssoAvailable, setSsoAvailable] = useState(false);
  const [mode, setMode] = useState<LoginMode>('LOCAL');
  const { loginSuccess } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    let cancelled = false;
    getSsoAvailability()
      .then((res) => {
        if (cancelled) {
          return;
        }
        setSsoAvailable(res.sso_available);
        // Where a directory exists it is the way most people get in, so it leads.
        setMode(res.sso_available ? 'SSO' : 'LOCAL');
      })
      .catch(() => {
        // Already reported by the interceptor; the local form stands on its own.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleFinish = async (values: FormValues) => {
    setSubmitting(true);
    try {
      const res = await login({ ...values, mode });
      loginSuccess(res.token, res.must_change_password);
      if (res.must_change_password) {
        navigate('/change-password', { replace: true });
        return;
      }
      const state = location.state as LocationState | null;
      // The permissions of the account that just signed in are not in context yet -- loginSuccess only
      // stored the token, and /auth/me is still in flight -- so a fresh sign-in goes to "/" and lets the
      // router pick the landing screen once they arrive. A deep link the visitor was bounced off is
      // honoured as-is.
      navigate(state?.from?.pathname ?? '/', { replace: true });
    } finally {
      setSubmitting(false);
    }
  };

  const form = (
    <Form<FormValues> layout="vertical" onFinish={handleFinish} autoComplete="off">
      <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
        <Input prefix={<UserOutlined />} placeholder={mode === 'SSO' ? '域账号' : 'admin'} />
      </Form.Item>
      <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
        <Input.Password
          prefix={<LockOutlined />}
          placeholder={mode === 'SSO' ? '域账号密码' : '请输入密码'}
        />
      </Form.Item>
      <Form.Item style={{ marginBottom: 0 }}>
        <Button type="primary" htmlType="submit" block loading={submitting}>
          登录
        </Button>
      </Form.Item>
    </Form>
  );

  const hint =
    mode === 'SSO' ? (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        使用公司域账号登录，首次登录将自动开通账号并授予默认角色。
      </Typography.Text>
    ) : (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        使用管理员创建的平台账号登录，如需开通请联系管理员。
      </Typography.Text>
    );

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
        background: '#f0f2f5',
      }}
    >
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
          企业RAG管理平台
        </Typography.Title>
        {ssoAvailable ? (
          <Tabs
            activeKey={mode}
            onChange={(key) => setMode(key as LoginMode)}
            items={[
              { key: 'SSO', label: '单点登录', children: form },
              { key: 'LOCAL', label: '平台账号', children: form },
            ]}
          />
        ) : (
          form
        )}
        <div style={{ marginTop: 16, textAlign: 'center' }}>{hint}</div>
      </Card>
      <Typography.Text type="secondary" style={{ fontSize: 12, marginTop: 16 }}>
        企业RAG管理平台 · Apache-2.0 · @author{' '}
        <Typography.Link href="mailto:owlzhangfq@gmail.com" style={{ fontSize: 12 }}>
          owlzhangfq@gmail.com
        </Typography.Link>
      </Typography.Text>
    </div>
  );
}

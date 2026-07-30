// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Button, Card, Divider, Form, Input, Space, Tabs, Typography, message } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { getSsoAvailability, getSsoProviders, login } from '../api/auth';
import type { LoginMode, SsoProviders } from '../api/types';
import { useAuth } from '../auth/AuthContext';

interface LocationState {
  from?: { pathname: string };
}

interface FormValues {
  username: string;
  password: string;
}

// M16-CONTRACTS.md section 5: the SSO callback 302s back here with the outcome in the URL
// fragment -- a fragment never reaches server logs or the Referer header, unlike a query string.
const SSO_TOKEN_PREFIX = '#sso_token=';
const SSO_ERROR_PREFIX = '#sso_error=';

/** Browser SSO entry: a full-page redirect, not an XHR -- the IdP has to see the user's browser. */
function startBrowserSso(protocol: 'oidc' | 'saml' | 'cas') {
  window.location.href = `/api/v1/auth/${protocol}/login`;
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
  const [ssoProviders, setSsoProviders] = useState<SsoProviders | null>(null);
  const [mode, setMode] = useState<LoginMode>('LOCAL');
  const { loginSuccess } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  // Consumes a browser-SSO callback outcome before anything renders twice: the token is stored and
  // the fragment removed from the address bar so a copied URL or a refresh does not replay it.
  useEffect(() => {
    const hash = window.location.hash;
    if (hash.startsWith(SSO_TOKEN_PREFIX)) {
      const token = decodeURIComponent(hash.slice(SSO_TOKEN_PREFIX.length));
      window.history.replaceState(null, '', window.location.pathname);
      // Browser SSO never forces a password change -- the IdP owns that credential, not us.
      loginSuccess(token, false);
      navigate('/', { replace: true });
    } else if (hash.startsWith(SSO_ERROR_PREFIX)) {
      const reason = decodeURIComponent(hash.slice(SSO_ERROR_PREFIX.length));
      window.history.replaceState(null, '', window.location.pathname);
      message.error(`单点登录失败：${reason}`);
    }
    // Runs once against the URL the page was opened with; loginSuccess/navigate are stable.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    // A failure means "no protocols": offering a redirect button that 302s onto an error page is
    // worse than not offering it, and the credential form above stays available either way.
    getSsoProviders()
      .then((res) => {
        if (!cancelled) {
          setSsoProviders(res);
        }
      })
      .catch(() => undefined);
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

  const ssoButtons = ssoProviders && (ssoProviders.oidc || ssoProviders.saml || ssoProviders.cas) && (
    <>
      <Divider plain>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          或通过企业单点登录
        </Typography.Text>
      </Divider>
      <Space direction="vertical" style={{ width: '100%' }}>
        {ssoProviders.oidc && (
          <Button block onClick={() => startBrowserSso('oidc')}>
            OIDC 单点登录
          </Button>
        )}
        {ssoProviders.saml && (
          <Button block onClick={() => startBrowserSso('saml')}>
            SAML 单点登录
          </Button>
        )}
        {ssoProviders.cas && (
          <Button block onClick={() => startBrowserSso('cas')}>
            CAS 单点登录
          </Button>
        )}
      </Space>
    </>
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
        {ssoButtons}
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

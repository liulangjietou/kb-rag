// Author: owlzhangfq@gmail.com
import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons';
import { App as AntApp, Button, Divider, Form, Input, Space, Tabs, Typography } from 'antd';
import type { FormEvent } from 'react';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { getSsoAvailability, getSsoProviders, login } from '../api/auth';
import type { LoginMode, SsoProviders } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import AuthShell from '../components/AuthShell';

interface LocationState {
  from?: { pathname: string };
}

interface FormValues {
  username: string;
  password: string;
}

const SSO_TOKEN_PREFIX = '#sso_token=';
const SSO_ERROR_PREFIX = '#sso_error=';

/** 浏览器 SSO 必须整页跳转，让身份提供方直接接管浏览器会话。 */
function startBrowserSso(protocol: 'oidc' | 'saml' | 'cas') {
  window.location.href = `/api/v1/auth/${protocol}/login`;
}

/** 支持目录账号、本地账号和企业 SSO 的统一登录入口。 */
export default function LoginPage() {
  const [submitting, setSubmitting] = useState(false);
  const [ssoAvailable, setSsoAvailable] = useState(false);
  const [ssoProviders, setSsoProviders] = useState<SsoProviders | null>(null);
  const [mode, setMode] = useState<LoginMode>('LOCAL');
  const [formInstance] = Form.useForm<FormValues>();
  const { message } = AntApp.useApp();
  const { loginSuccess } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  // SSO 结果放在 fragment 中，消费后立即清理，避免刷新重放或复制泄漏。
  useEffect(() => {
    const hash = window.location.hash;
    if (hash.startsWith(SSO_TOKEN_PREFIX)) {
      const token = decodeURIComponent(hash.slice(SSO_TOKEN_PREFIX.length));
      window.history.replaceState(null, '', window.location.pathname);
      loginSuccess(token, false);
      navigate('/', { replace: true });
    } else if (hash.startsWith(SSO_ERROR_PREFIX)) {
      const reason = decodeURIComponent(hash.slice(SSO_ERROR_PREFIX.length));
      window.history.replaceState(null, '', window.location.pathname);
      message.error(`单点登录失败：${reason}`);
    }
    // 只消费页面首次打开时携带的 fragment。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    let cancelled = false;
    getSsoAvailability()
      .then((res) => {
        if (!cancelled) {
          setSsoAvailable(res.sso_available);
          setMode(res.sso_available ? 'SSO' : 'LOCAL');
        }
      })
      .catch(() => undefined);
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
      navigate(state?.from?.pathname ?? '/', { replace: true });
    } finally {
      setSubmitting(false);
    }
  };

  // 部分浏览器的密码管理器不会触发 React change；提交捕获阶段以原生 FormData 同步真实值。
  const syncAutofillBeforeSubmit = (event: FormEvent<HTMLFormElement>) => {
    const formData = new FormData(event.currentTarget);
    const username = formData.get('username');
    const password = formData.get('password');
    formInstance.setFieldsValue({
      ...(typeof username === 'string' ? { username } : {}),
      ...(typeof password === 'string' ? { password } : {}),
    });
  };

  const handleModeChange = (nextMode: string) => {
    setMode(nextMode as LoginMode);
    formInstance.setFieldValue('password', '');
  };

  const credentialForm = (
    <Form<FormValues>
      className="login-form"
      form={formInstance}
      name={`login-${mode.toLowerCase()}`}
      layout="vertical"
      onFinish={handleFinish}
      onSubmitCapture={syncAutofillBeforeSubmit}
      autoComplete="on"
    >
      <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
        <Input
          name="username"
          autoComplete="username"
          prefix={<UserOutlined />}
          placeholder={mode === 'SSO' ? '输入域账号' : '输入平台账号'}
        />
      </Form.Item>
      <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
        <Input.Password
          name="password"
          autoComplete="current-password"
          prefix={<LockOutlined />}
          placeholder={mode === 'SSO' ? '输入域账号密码' : '输入平台密码'}
        />
      </Form.Item>
      <Form.Item className="login-form__submit">
        <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
          进入工作台
        </Button>
      </Form.Item>
    </Form>
  );

  const hint = mode === 'SSO'
    ? '使用企业目录账号登录，首次登录将自动开通账号并授予默认角色。'
    : '使用管理员创建的平台账号登录，如需开通请联系管理员。';

  const hasBrowserSso = Boolean(ssoProviders && (ssoProviders.oidc || ssoProviders.saml || ssoProviders.cas));

  return (
    <AuthShell
      eyebrow="SECURE ACCESS"
      headline="让每一次回答，都能回到可信证据。"
      description="统一管理知识、检索、应用与评测，让企业 RAG 从资料接入到质量闭环始终可见、可控、可追溯。"
    >
      <div className="auth-environment"><i aria-hidden="true" /> 管理控制台</div>
      <Typography.Title level={2}>欢迎回来</Typography.Title>
      <Typography.Paragraph type="secondary">请选择适合你的身份方式继续。</Typography.Paragraph>

      {ssoAvailable && (
        <Tabs
          className="login-mode-tabs"
          activeKey={mode}
          onChange={handleModeChange}
          items={[
            { key: 'SSO', label: '域账号' },
            { key: 'LOCAL', label: '平台账号' },
          ]}
        />
      )}
      {credentialForm}

      {hasBrowserSso && (
        <>
          <Divider plain>或通过企业单点登录</Divider>
          <Space className="sso-actions" direction="vertical">
            {ssoProviders?.oidc && <Button block onClick={() => startBrowserSso('oidc')}>OIDC 单点登录</Button>}
            {ssoProviders?.saml && <Button block onClick={() => startBrowserSso('saml')}>SAML 单点登录</Button>}
            {ssoProviders?.cas && <Button block onClick={() => startBrowserSso('cas')}>CAS 单点登录</Button>}
          </Space>
        </>
      )}

      <div className="auth-hint">{hint}</div>
      <div className="auth-security-note">
        <SafetyCertificateOutlined />
        账号与密码仅用于本次认证，不会由页面持久化保存。
      </div>
    </AuthShell>
  );
}

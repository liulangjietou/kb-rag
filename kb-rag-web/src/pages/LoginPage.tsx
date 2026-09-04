// Author: owlzhangfq@gmail.com
import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons';
import { App as AntApp, Button, Checkbox, Divider, Form, Input, Space, Tabs, Typography } from 'antd';
import type { FormEvent } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { getSsoAvailability, getSsoProviders, login } from '../api/auth';
import type { LoginMode, SsoProviders } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import AuthShell from '../components/AuthShell';
import LoginSliderCaptcha from '../components/LoginSliderCaptcha';
import { clearLoginMemory, loadLoginMemory, saveLoginMemory, storePasswordCredential } from '../utils/loginMemory';

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
  const [initialLoginMemory] = useState(loadLoginMemory);
  const [submitting, setSubmitting] = useState(false);
  const [ssoAvailable, setSsoAvailable] = useState(false);
  const [ssoProviders, setSsoProviders] = useState<SsoProviders | null>(null);
  const [mode, setMode] = useState<LoginMode>('LOCAL');
  const [rememberCredentials, setRememberCredentials] = useState(initialLoginMemory.remember);
  const [captchaResetKey, setCaptchaResetKey] = useState(0);
  const [captchaVerified, setCaptchaVerified] = useState(false);
  const [formInstance] = Form.useForm<FormValues>();
  const formHostRef = useRef<HTMLDivElement>(null);
  const captchaProofRef = useRef<string | null>(null);
  const loginInFlightRef = useRef(false);
  const rememberCredentialsRef = useRef(initialLoginMemory.remember);
  const submitRequestedRef = useRef(false);
  const usernamesRef = useRef({ ...initialLoginMemory.usernames });
  const { message } = AntApp.useApp();
  const { loginSuccess } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const resetCaptcha = useCallback(() => {
    captchaProofRef.current = null;
    submitRequestedRef.current = false;
    setCaptchaVerified(false);
    setCaptchaResetKey((current) => current + 1);
  }, []);

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
          const currentValues = formInstance.getFieldsValue();
          const nativeForm = formHostRef.current?.querySelector('form');
          const nativeValues = nativeForm instanceof HTMLFormElement ? new FormData(nativeForm) : null;
          const hasCredentialInput = formInstance.isFieldsTouched()
            || loginInFlightRef.current
            || submitRequestedRef.current
            || Boolean(currentValues.username || currentValues.password)
            || Boolean(nativeValues?.get('username') || nativeValues?.get('password'));
          if (res.sso_available && !hasCredentialInput) {
            setMode('SSO');
            formInstance.setFieldsValue({
              username: initialLoginMemory.remember ? initialLoginMemory.usernames.SSO ?? '' : '',
              password: '',
            });
            resetCaptcha();
          }
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
  }, [formInstance, initialLoginMemory, resetCaptcha]);

  const handleFinish = async (values: FormValues) => {
    const captchaProof = captchaProofRef.current;
    if (!captchaProof || loginInFlightRef.current) {
      return;
    }
    loginInFlightRef.current = true;
    setSubmitting(true);
    try {
      const res = await login({ ...values, mode, captcha_proof: captchaProof });
      if (rememberCredentialsRef.current) {
        usernamesRef.current[mode] = values.username;
        saveLoginMemory({ remember: true, usernames: usernamesRef.current });
        await storePasswordCredential(values.username, values.password);
      } else {
        clearLoginMemory();
      }
      loginSuccess(res.token, res.must_change_password);
      if (res.must_change_password) {
        navigate('/change-password', { replace: true });
        return;
      }
      const state = location.state as LocationState | null;
      navigate(state?.from?.pathname ?? '/', { replace: true });
    } catch {
      // 共享请求拦截器已展示后端错误；这里只重置一次性 proof，保留账号和密码。
      resetCaptcha();
    } finally {
      loginInFlightRef.current = false;
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
    const typedUsername = formInstance.getFieldValue('username');
    if (typeof typedUsername === 'string') {
      usernamesRef.current[mode] = typedUsername;
    }
    const selectedMode = nextMode as LoginMode;
    setMode(selectedMode);
    formInstance.setFieldsValue({
      username: usernamesRef.current[selectedMode] ?? '',
      password: '',
    });
    resetCaptcha();
  };

  const handleRememberChange = (checked: boolean) => {
    // 登录请求可能尚未返回，成功回调必须读取用户最新的授权选择。
    rememberCredentialsRef.current = checked;
    setRememberCredentials(checked);
    if (checked) {
      saveLoginMemory({ remember: true, usernames: usernamesRef.current });
    } else {
      clearLoginMemory();
    }
  };

  const handleCaptchaVerified = (captchaProof: string) => {
    if (submitRequestedRef.current || loginInFlightRef.current) {
      return;
    }
    captchaProofRef.current = captchaProof;
    submitRequestedRef.current = true;
    setCaptchaVerified(true);
    const nativeForm = formHostRef.current?.querySelector('form');
    if (!(nativeForm instanceof HTMLFormElement)) {
      resetCaptcha();
      return;
    }
    nativeForm.requestSubmit();
  };

  const credentialForm = (
    <div ref={formHostRef}>
      <Form<FormValues>
        className="login-form"
        form={formInstance}
        name={`login-${mode.toLowerCase()}`}
        initialValues={{
          username: initialLoginMemory.remember ? initialLoginMemory.usernames.LOCAL ?? '' : '',
        }}
        layout="vertical"
        onFinish={handleFinish}
        onFinishFailed={resetCaptcha}
        onSubmitCapture={syncAutofillBeforeSubmit}
        autoComplete="on"
      >
        <Form.Item
          name="username"
          label={mode === 'SSO' ? '域用户名' : '邮箱或平台用户名'}
          rules={[{ required: true, message: mode === 'SSO' ? '请输入域用户名' : '请输入邮箱或平台用户名' }]}
        >
          <Input
            name="username"
            autoComplete="username"
            maxLength={254}
            prefix={<UserOutlined />}
            placeholder={mode === 'SSO' ? '输入域账号' : '输入邮箱或平台用户名'}
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
        <Form.Item>
          <Checkbox
            checked={rememberCredentials}
            onChange={(event) => handleRememberChange(event.target.checked)}
          >
            记住用户名和密码
          </Checkbox>
        </Form.Item>
        <LoginSliderCaptcha
          disabled={submitting}
          resetKey={captchaResetKey}
          onVerified={handleCaptchaVerified}
        />
        <Form.Item className="login-form__submit">
          <Button
            type="primary"
            htmlType="submit"
            block
            size="large"
            loading={submitting}
            disabled={!captchaVerified}
          >
            进入工作台
          </Button>
        </Form.Item>
      </Form>
    </div>
  );

  const hint = mode === 'SSO'
    ? '使用企业目录账号登录，首次登录将自动开通账号并授予默认角色。'
    : '使用已审核的邮箱，或管理员创建的平台用户名登录。';

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
            { key: 'SSO', label: '域账号', disabled: submitting },
            { key: 'LOCAL', label: '平台账号', disabled: submitting },
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
      {mode === 'LOCAL' && (
        <div className="registration-login-entry">
          还没有账号？ <Link to="/register">使用工作邮箱注册</Link>
        </div>
      )}
      <div className="auth-security-note">
        <SafetyCertificateOutlined />
        页面只保存用户名；密码由浏览器密码管理器保护，不写入站点存储。
      </div>
    </AuthShell>
  );
}

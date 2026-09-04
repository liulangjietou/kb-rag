// Author: owlzhangfq@gmail.com
import {
  CheckCircleOutlined,
  LockOutlined,
  MailOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Alert, Button, Form, Input, Modal, Typography } from 'antd';
import { isAxiosError } from 'axios';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  createRegistration,
  sendRegistrationCode,
  verifyRegistrationEmail,
} from '../api/registration';
import type { CreateRegistrationResponse } from '../api/registrationTypes';
import AuthShell from '../components/AuthShell';
import LoginSliderCaptcha from '../components/LoginSliderCaptcha';
import '../styles/registration-home.css';
import { isStrongPassword } from '../utils/registrationPassword';

interface EmailFormValues {
  email: string;
}

interface AccountFormValues {
  display_name: string;
  team_name: string;
  password: string;
  confirm_password: string;
  application_note: string;
}

const REGISTRATION_RECEIPT_KEY = 'kb-rag-registration-receipt';
const REGISTRATION_SUBMISSION_ID_KEY = 'kb-rag-registration-submission-id';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SUBMISSION_OUTCOME_UNKNOWN_MESSAGE = '提交结果待确认，请点击“提交注册申请”重试确认；若服务端未保存，会提示重新验证邮箱。';

function hasReusableSubmissionId(): boolean {
  const submissionId = window.sessionStorage.getItem(REGISTRATION_SUBMISSION_ID_KEY);
  return submissionId !== null && UUID_PATTERN.test(submissionId);
}

/** 网络失败重试复用同一标识；成功、换邮箱或票据失效后清除。 */
function getOrCreateSubmissionId(): string {
  const existing = window.sessionStorage.getItem(REGISTRATION_SUBMISSION_ID_KEY);
  if (existing && UUID_PATTERN.test(existing)) return existing;
  const created = window.crypto.randomUUID();
  window.sessionStorage.setItem(REGISTRATION_SUBMISSION_ID_KEY, created);
  return created;
}

function countdownLabel(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

/** 只恢复非敏感回执；ticket、验证码和密码永不进入浏览器存储。 */
function loadRegistrationReceipt(): CreateRegistrationResponse | null {
  try {
    const raw = window.sessionStorage.getItem(REGISTRATION_RECEIPT_KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<CreateRegistrationResponse>;
    if (
      typeof value.application_id !== 'string'
      || typeof value.email !== 'string'
      || typeof value.created_at !== 'string'
      || !['PENDING', 'APPROVED', 'REJECTED'].includes(value.status ?? '')
    ) {
      window.sessionStorage.removeItem(REGISTRATION_RECEIPT_KEY);
      return null;
    }
    return value as CreateRegistrationResponse;
  } catch {
    window.sessionStorage.removeItem(REGISTRATION_RECEIPT_KEY);
    return null;
  }
}

function RegistrationSteps({ current }: { current: 1 | 2 | 3 }) {
  const labels = ['邮箱验证', '账户信息', '管理员审核'];
  return (
    <ol className="registration-steps" aria-label="注册进度">
      {labels.map((label, index) => {
        const step = (index + 1) as 1 | 2 | 3;
        const state = step < current ? 'is-done' : step === current ? 'is-active' : '';
        return (
          <li key={label} className={state} aria-current={step === current ? 'step' : undefined}>
            <span>{step < current ? '✓' : `0${step}`}</span>
            <strong>{label}</strong>
          </li>
        );
      })}
    </ol>
  );
}

/** 邮箱所有权与账户资料分步提交；只有服务端 ticket 能把两步绑定在一起。 */
export default function RegisterPage() {
  const [step, setStep] = useState<1 | 2>(1);
  const [emailForm] = Form.useForm<EmailFormValues>();
  const [accountForm] = Form.useForm<AccountFormValues>();
  const [captchaOpen, setCaptchaOpen] = useState(false);
  const [captchaResetKey, setCaptchaResetKey] = useState(0);
  const [sendingCode, setSendingCode] = useState(false);
  const [verifyingEmail, setVerifyingEmail] = useState(false);
  const [verificationCode, setVerificationCode] = useState('');
  const [verificationCodeError, setVerificationCodeError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [ticket, setTicket] = useState<string | null>(null);
  const [ticketExpiresAt, setTicketExpiresAt] = useState<number | null>(null);
  const [ticketCountdown, setTicketCountdown] = useState(0);
  const [verifiedEmail, setVerifiedEmail] = useState('');
  const [result, setResult] = useState<CreateRegistrationResponse | null>(loadRegistrationReceipt);
  const [inlineError, setInlineError] = useState<string | null>(null);
  const requestSequenceRef = useRef(0);
  const sendInFlightRef = useRef(false);
  const verifyInFlightRef = useRef(false);
  const submitInFlightRef = useRef(false);

  useEffect(() => {
    if (countdown <= 0) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      setCountdown((current) => Math.max(0, current - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [countdown]);

  useEffect(() => {
    if (step !== 2 || ticketExpiresAt === null) {
      return undefined;
    }
    const refreshTicketCountdown = () => {
      const remaining = Math.max(0, Math.ceil((ticketExpiresAt - Date.now()) / 1000));
      setTicketCountdown(remaining);
      // 已发出的提交由服务端决定成功或失败；此时切回第一步会丢掉成功回执，并让旧请求
      // 的 finally 无法释放互斥标记。只有没有提交在途时，浏览器计时器才主动失效票据。
      if (remaining === 0 && !submitInFlightRef.current && hasReusableSubmissionId()) {
        // 请求可能已在服务端提交成功，只是响应丢失。保留内存中的 ticket 和稳定提交标识，
        // 允许用户用同一请求找回原申请；服务端会在检查票据过期前先查幂等回执。
        setInlineError(SUBMISSION_OUTCOME_UNKNOWN_MESSAGE);
        return;
      }
      if (remaining === 0 && !submitInFlightRef.current) {
        window.sessionStorage.removeItem(REGISTRATION_SUBMISSION_ID_KEY);
        setTicket(null);
        setTicketExpiresAt(null);
        setStep(1);
        accountForm.resetFields();
        setVerificationCode('');
        setVerificationCodeError(null);
        setInlineError('邮箱验证票据已过期，请重新验证邮箱后再填写账户信息。');
      }
    };
    refreshTicketCountdown();
    const timer = window.setInterval(refreshTicketCountdown, 1000);
    return () => window.clearInterval(timer);
  }, [accountForm, emailForm, step, ticketExpiresAt]);

  useEffect(() => () => {
    requestSequenceRef.current += 1;
  }, []);

  const normalizeEmail = () => {
    return (emailForm.getFieldValue('email') ?? '').trim().toLowerCase();
  };

  const openCaptcha = async () => {
    if (verifyInFlightRef.current) return;
    normalizeEmail();
    try {
      await emailForm.validateFields(['email']);
    } catch {
      return;
    }
    setInlineError(null);
    setCaptchaResetKey((current) => current + 1);
    setCaptchaOpen(true);
  };

  const handleCaptchaVerified = async (captchaProof: string) => {
    if (sendInFlightRef.current || verifyInFlightRef.current) {
      return;
    }
    const email = normalizeEmail();
    const sequence = ++requestSequenceRef.current;
    sendInFlightRef.current = true;
    setSendingCode(true);
    setInlineError(null);
    try {
      const response = await sendRegistrationCode({ email, captcha_proof: captchaProof });
      if (requestSequenceRef.current !== sequence) {
        return;
      }
      setCountdown(Math.max(1, response.resend_after_seconds));
      setVerificationCode('');
      setVerificationCodeError(null);
      setCaptchaOpen(false);
    } catch {
      if (requestSequenceRef.current === sequence) {
        setInlineError('验证码暂未发送，请检查邮箱后重试。');
        setCaptchaResetKey((current) => current + 1);
      }
    } finally {
      if (requestSequenceRef.current === sequence) {
        sendInFlightRef.current = false;
        setSendingCode(false);
      }
    }
  };

  const verifyEmail = async (values: EmailFormValues) => {
    if (verifyInFlightRef.current || sendInFlightRef.current) {
      return;
    }
    const code = verificationCode.trim();
    if (!/^\d{6}$/.test(code)) {
      setVerificationCodeError(code ? '验证码必须是 6 位数字' : '请输入邮箱验证码');
      return;
    }
    const email = values.email.trim().toLowerCase();
    const sequence = ++requestSequenceRef.current;
    verifyInFlightRef.current = true;
    setVerifyingEmail(true);
    setInlineError(null);
    try {
      const response = await verifyRegistrationEmail({ email, code });
      if (requestSequenceRef.current !== sequence) {
        return;
      }
      const expiresInSeconds = Math.max(1, Math.floor(response.expires_in_seconds));
      setTicket(response.registration_ticket);
      window.sessionStorage.removeItem(REGISTRATION_SUBMISSION_ID_KEY);
      setTicketExpiresAt(Date.now() + expiresInSeconds * 1000);
      setTicketCountdown(expiresInSeconds);
      setVerifiedEmail(email);
      setVerificationCode('');
      setVerificationCodeError(null);
      setStep(2);
    } catch {
      if (requestSequenceRef.current === sequence) {
        setInlineError('邮箱验证码无效或已过期，请重新获取后再试。');
      }
    } finally {
      if (requestSequenceRef.current === sequence) {
        verifyInFlightRef.current = false;
        setVerifyingEmail(false);
      }
    }
  };

  const submitRegistration = async (values: AccountFormValues) => {
    const canRecoverUnknownSubmission = hasReusableSubmissionId();
    if (
      !ticket
      || ticketExpiresAt === null
      || (Date.now() >= ticketExpiresAt && !canRecoverUnknownSubmission)
    ) {
      window.sessionStorage.removeItem(REGISTRATION_SUBMISSION_ID_KEY);
      setTicket(null);
      setTicketExpiresAt(null);
      setTicketCountdown(0);
      setStep(1);
      accountForm.resetFields();
      setVerificationCode('');
      setVerificationCodeError(null);
      setInlineError('邮箱验证票据已过期，请重新验证邮箱后再提交。');
      return;
    }
    if (submitInFlightRef.current) {
      return;
    }
    const sequence = ++requestSequenceRef.current;
    submitInFlightRef.current = true;
    setSubmitting(true);
    setInlineError(null);
    try {
      const submissionId = getOrCreateSubmissionId();
      const response = await createRegistration({
        registration_ticket: ticket,
        client_submission_id: submissionId,
        display_name: values.display_name.trim(),
        team_name: values.team_name.trim(),
        password: values.password,
        application_note: values.application_note.trim(),
      });
      if (requestSequenceRef.current === sequence) {
        window.sessionStorage.setItem(REGISTRATION_RECEIPT_KEY, JSON.stringify({
          application_id: response.application_id,
          email: response.email,
          status: response.status,
          created_at: response.created_at,
        }));
        window.sessionStorage.removeItem(REGISTRATION_SUBMISSION_ID_KEY);
        setResult(response);
      }
    } catch (error) {
      if (requestSequenceRef.current === sequence) {
        const responseStatus = isAxiosError(error) ? error.response?.status : undefined;
        const definitiveClientRejection = responseStatus !== undefined
          && responseStatus >= 400
          && responseStatus < 500
          && responseStatus !== 408
          && responseStatus !== 429;
        const ticketExpired = ticketExpiresAt !== null && Date.now() >= ticketExpiresAt;
        if (ticketExpired && definitiveClientRejection) {
          window.sessionStorage.removeItem(REGISTRATION_SUBMISSION_ID_KEY);
          setTicket(null);
          setTicketExpiresAt(null);
          setTicketCountdown(0);
          setStep(1);
          accountForm.resetFields();
          setVerificationCode('');
          setVerificationCodeError(null);
          setInlineError('邮箱验证票据已过期且申请未提交，请重新验证邮箱。');
        } else if (ticketExpired) {
          setTicketCountdown(0);
          setInlineError(SUBMISSION_OUTCOME_UNKNOWN_MESSAGE);
        } else {
          setInlineError('注册申请未提交，请检查信息后重试。');
        }
      }
    } finally {
      if (requestSequenceRef.current === sequence) {
        submitInFlightRef.current = false;
        setSubmitting(false);
      }
    }
  };

  const handleEmailChanged = () => {
    window.sessionStorage.removeItem(REGISTRATION_SUBMISSION_ID_KEY);
    requestSequenceRef.current += 1;
    sendInFlightRef.current = false;
    verifyInFlightRef.current = false;
    setSendingCode(false);
    setVerifyingEmail(false);
    setCountdown(0);
    // 验证码由当前页面状态持有，邮箱变化无需在输入事件中嵌套修改 rc-field-form store。
    setVerificationCode('');
    setVerificationCodeError(null);
    setTicket(null);
    setTicketExpiresAt(null);
    setTicketCountdown(0);
    setInlineError(null);
  };

  if (result) {
    const pending = result.status === 'PENDING';
    const approved = result.status === 'APPROVED';
    return (
      <AuthShell
        eyebrow="APPLICATION RECEIVED"
        headline="身份先可信，权限再抵达。"
        description="邮箱验证证明你是谁，管理员审核决定你能做什么。身份与权限分开，知识边界才始终清晰。"
      >
        <div className="registration-flow registration-pending" aria-live="polite">
          <div className="registration-pending__seal" aria-hidden="true"><CheckCircleOutlined /></div>
          <Typography.Title level={2}>
            {pending ? '注册申请已提交' : approved ? '注册申请已通过' : '注册申请未通过'}
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            {pending
              ? `${result.email} 已通过邮箱验证。本页仅保存提交回执，不提供实时审核状态；当前审核结果以邮件通知为准。`
              : approved
                ? `${result.email} 的申请已通过，可以返回登录页使用注册邮箱登录。`
                : `${result.email} 的申请未通过，请查看审核邮件中的原因后重新提交。`}
          </Typography.Paragraph>
          <div className="registration-reference">
            <span>申请编号</span>
            <code>{result.application_id}</code>
          </div>
          <ol className="registration-timeline">
            <li className="is-done"><span>✓</span><div><strong>邮箱验证完成</strong><p>验证码已一次性作废。</p></div></li>
            <li className={pending ? 'is-current' : 'is-done'}><span>{pending ? '•' : '✓'}</span><div><strong>管理员处理申请</strong><p>{pending ? '本页不展示实时进度，请留意审核结果邮件。' : '审核已经完成。'}</p></div></li>
            <li className={pending ? undefined : 'is-current'}><span>3</span><div><strong>{pending ? '审核结果通知' : approved ? '账号已开通' : '申请需调整'}</strong><p>{pending ? '邮件会说明申请结果；通过后可使用邮箱登录。' : approved ? '现在可以使用注册邮箱登录。' : '请按审核邮件说明修正后重新申请。'}</p></div></li>
          </ol>
          <Link className="registration-primary-link" to="/login">返回登录页</Link>
          <Button
            className="registration-secondary-action"
            type="link"
            block
            onClick={() => {
              window.sessionStorage.removeItem(REGISTRATION_RECEIPT_KEY);
              window.sessionStorage.removeItem(REGISTRATION_SUBMISSION_ID_KEY);
              setResult(null);
              setStep(1);
              setTicket(null);
              setTicketExpiresAt(null);
              setTicketCountdown(0);
              setVerifiedEmail('');
              setVerificationCode('');
              setVerificationCodeError(null);
              emailForm.resetFields();
              accountForm.resetFields();
            }}
          >
            注册其他邮箱
          </Button>
          <Alert
            className="registration-note"
            type="info"
            showIcon
            message="审核前不会签发业务访问令牌。"
          />
        </div>
      </AuthShell>
    );
  }

  return (
    <AuthShell
      eyebrow="VERIFIED REGISTRATION"
      headline="从可信身份，走进企业知识网络。"
      description="从工作邮箱验证开始，建立可识别、可审核、可追溯的企业知识访问身份。"
    >
      <div className="registration-flow">
        <Typography.Title level={2}>创建平台账号</Typography.Title>
        <Typography.Paragraph type="secondary">先验证邮箱，再提交账户资料等待管理员审核。</Typography.Paragraph>
        <RegistrationSteps current={step} />

        {inlineError && <Alert className="registration-inline-error" type="error" showIcon message={inlineError} />}

        {step === 1 ? (
          <Form<EmailFormValues>
            form={emailForm}
            layout="vertical"
            requiredMark={false}
            onFinish={verifyEmail}
            autoComplete="off"
          >
            <Form.Item
              name="email"
              label="工作邮箱"
              extra="审核通过后，这个邮箱就是你的完整登录名。"
              normalize={(value: string) => value.trim().toLowerCase()}
              rules={[
                { required: true, message: '请输入工作邮箱' },
                { type: 'email', message: '请输入有效的邮箱地址' },
                { max: 254, message: '邮箱长度不能超过 254 个字符' },
              ]}
            >
              <Input
                prefix={<MailOutlined />}
                placeholder="name@company.com"
                autoComplete="email"
                onChange={handleEmailChanged}
              />
            </Form.Item>
            <Form.Item
              label="邮箱验证码"
              required
              validateStatus={verificationCodeError ? 'error' : undefined}
              help={verificationCodeError}
            >
              <div className="registration-code-row">
                <Input
                  prefix={<SafetyCertificateOutlined />}
                  placeholder="输入 6 位验证码"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  value={verificationCode}
                  onChange={(event) => {
                    setVerificationCode(event.target.value);
                    setVerificationCodeError(null);
                  }}
                />
                <Button
                  className="registration-code-button"
                  disabled={countdown > 0 || sendingCode || verifyingEmail}
                  loading={sendingCode}
                  onClick={() => void openCaptcha()}
                >
                  {countdown > 0 ? `${countdown} 秒后重发` : '获取验证码'}
                </Button>
              </div>
            </Form.Item>
            <Alert
              className="registration-note"
              type="info"
              showIcon
              message="获取验证码前需要完成滑块验证，具体有效期以验证码邮件提示为准。"
            />
            <Button
              type="primary"
              htmlType="submit"
              block
              size="large"
              disabled={sendingCode}
              loading={verifyingEmail}
            >
              验证邮箱并继续
            </Button>
          </Form>
        ) : (
          <Form<AccountFormValues>
            form={accountForm}
            layout="vertical"
            requiredMark={false}
            onFinish={submitRegistration}
            autoComplete="off"
          >
            <div className="registration-verified-email">
              <CheckCircleOutlined />
              <div>
                <strong>邮箱已验证</strong>
                <span>{verifiedEmail} · 请在 {countdownLabel(ticketCountdown)} 内提交</span>
              </div>
            </div>
            <div className="registration-form-grid">
              <Form.Item
                name="display_name"
                label="姓名"
                rules={[
                  { required: true, whitespace: true, message: '请输入姓名' },
                  { max: 64, message: '姓名不能超过 64 个字符' },
                ]}
              >
                <Input prefix={<UserOutlined />} placeholder="你的姓名" autoComplete="name" />
              </Form.Item>
              <Form.Item
                name="team_name"
                label="团队 / 部门"
                rules={[
                  { required: true, whitespace: true, message: '请输入团队或部门' },
                  { max: 128, message: '团队名称不能超过 128 个字符' },
                ]}
              >
                <Input prefix={<TeamOutlined />} placeholder="例如：AI 平台组" />
              </Form.Item>
              <Form.Item
                name="password"
                label="设置密码"
                extra="至少 12 个字符、UTF-8 不超过 72 字节，包含大小写字母、数字和符号。"
                rules={[
                  { required: true, message: '请设置密码' },
                  {
                    validator: (_, value: string | undefined) => !value || isStrongPassword(value)
                      ? Promise.resolve()
                      : Promise.reject(new Error('密码至少 12 个字符、UTF-8 不超过 72 字节，且包含大小写字母、数字和符号，不得含空白字符')),
                  },
                ]}
              >
                <Input.Password
                  prefix={<LockOutlined />}
                  placeholder="设置安全密码"
                  autoComplete="new-password"
                  maxLength={72}
                />
              </Form.Item>
              <Form.Item
                name="confirm_password"
                label="确认密码"
                dependencies={['password']}
                rules={[
                  { required: true, message: '请再次输入密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      return !value || getFieldValue('password') === value
                        ? Promise.resolve()
                        : Promise.reject(new Error('两次输入的密码不一致'));
                    },
                  }),
                ]}
              >
                <Input.Password
                  prefix={<CheckCircleOutlined />}
                  placeholder="再次输入密码"
                  autoComplete="new-password"
                  maxLength={72}
                />
              </Form.Item>
            </div>
            <Form.Item
              name="application_note"
              label="申请说明"
              extra="说明你需要完成的工作；权限和角色由管理员审核决定。"
              rules={[
                { required: true, whitespace: true, message: '请填写申请说明' },
                { min: 5, message: '申请说明至少 5 个字符' },
                { max: 500, message: '申请说明不能超过 500 个字符' },
              ]}
            >
              <Input.TextArea rows={3} placeholder="例如：负责产品知识库维护与内容发布" />
            </Form.Item>
            <div className="registration-form-actions">
              <Button
                size="large"
                disabled={submitting}
                onClick={() => {
                  requestSequenceRef.current += 1;
                  setTicket(null);
                  setTicketExpiresAt(null);
                  setTicketCountdown(0);
                  setStep(1);
                  setInlineError(null);
                }}
              >
                上一步
              </Button>
              <Button type="primary" htmlType="submit" size="large" loading={submitting}>
                提交注册申请
              </Button>
            </div>
          </Form>
        )}

        <div className="registration-login-link">已有账号？ <Link to="/login">返回登录</Link></div>
      </div>

      <Modal
        open={captchaOpen}
        title="先完成安全验证"
        footer={null}
        destroyOnHidden
        maskClosable={!sendingCode}
        closable={!sendingCode}
        onCancel={() => setCaptchaOpen(false)}
      >
        <Typography.Paragraph type="secondary">
          滑块用于保护匿名发信接口；邮箱验证码用于证明你拥有该邮箱。
        </Typography.Paragraph>
        <LoginSliderCaptcha
          disabled={sendingCode}
          resetKey={captchaResetKey}
          onVerified={handleCaptchaVerified}
        />
        <Button block disabled={sendingCode} onClick={() => setCaptchaOpen(false)}>取消</Button>
      </Modal>
    </AuthShell>
  );
}

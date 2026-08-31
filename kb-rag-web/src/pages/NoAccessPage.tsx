// Author: owlzhangfq@gmail.com
import { LogoutOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Button, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import AuthShell from '../components/AuthShell';

/** 账号已认证但没有任何控制台权限时的明确终点，避免空壳或重定向循环。 */
export default function NoAccessPage() {
  const { displayName, username, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <AuthShell
      eyebrow="ACCESS REQUIRED"
      headline="权限透明，才能让协作边界清晰。"
      description="平台会同时在页面、路由和服务端校验访问范围，确保每一份知识只对正确的人开放。"
      compactCard
    >
      <div className="auth-status-icon auth-status-icon--warning"><SafetyCertificateOutlined /></div>
      <Typography.Title level={2}>当前账号没有可用页面</Typography.Title>
      <Typography.Paragraph type="secondary">
        账号已成功登录，但尚未被授予知识库、评测或平台管理权限。请联系管理员分配角色。
      </Typography.Paragraph>
      <div className="auth-account-row">
        <span>当前账号</span>
        <strong>{displayName ?? username ?? '已登录账号'}</strong>
      </div>
      <Button type="primary" size="large" block icon={<LogoutOutlined />} onClick={handleLogout}>
        退出并返回登录页
      </Button>
    </AuthShell>
  );
}

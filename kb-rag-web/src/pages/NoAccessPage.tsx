// Author: owlzhangfq@gmail.com
// Landing page for a session that authenticated successfully but holds no console permission at all --
// the usual case being an LDAP account whose first login just created it with a viewer role that has not
// been granted anything yet. Telling the operator that plainly beats an empty shell or a redirect loop.
import { LogoutOutlined } from '@ant-design/icons';
import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export default function NoAccessPage() {
  const { displayName, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
      <Result
        status="warning"
        title="账号暂无可访问的功能"
        subTitle={`当前登录账号${displayName ? `（${displayName}）` : ''}尚未被授予任何权限，请联系管理员分配角色。`}
        extra={
          <Button icon={<LogoutOutlined />} onClick={handleLogout}>
            退出登录
          </Button>
        }
      />
    </div>
  );
}

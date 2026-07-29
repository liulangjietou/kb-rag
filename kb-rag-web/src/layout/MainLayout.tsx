import { LogoutOutlined } from '@ant-design/icons';
import { Alert, Layout, Menu, Space, Typography } from 'antd';
import { useMemo } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useModelStatus } from '../context/ModelStatusContext';
import { landingPath, visibleNavEntries } from './navigation';

const { Header, Sider, Content, Footer } = Layout;

/**
 * Authenticated app shell: left sider navigation + top header + content outlet.
 * Also owns the global "embedding not configured" banner (M1-CONTRACTS.md section 7).
 *
 * <p>The menu lists only what the account may open. That is a courtesy, not a control: every screen
 * behind it is guarded again by the router and once more by the server.
 */
export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { displayName, canAny, logout } = useAuth();
  const { modelStatus } = useModelStatus();

  const menuItems = useMemo(
    () => visibleNavEntries(canAny).map(({ key, icon, label }) => ({ key, icon, label })),
    [canAny],
  );
  // Longest prefix wins so that /kb/:kbId does not also light up a future /kb-something entry, and the
  // fallback follows the same rule the router uses for "/" instead of assuming /kb is reachable.
  const selectedKey =
    menuItems
      .map((item) => item.key)
      .filter((key) => location.pathname === key || location.pathname.startsWith(`${key}/`))
      .sort((a, b) => b.length - a.length)[0] ?? landingPath(canAny);

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth={0}>
        <div
          style={{
            height: 48,
            margin: 16,
            color: '#fff',
            fontWeight: 600,
            fontSize: 16,
            textAlign: 'center',
            overflow: 'hidden',
          }}
        >
          企业RAG管理平台
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', display: 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>
          <Space>
            <Typography.Text>{displayName ?? ''}</Typography.Text>
            <a onClick={handleLogout}>
              <LogoutOutlined /> 退出登录
            </a>
          </Space>
        </Header>
        {modelStatus && !modelStatus.embedding_configured && (
          <Alert
            banner
            type="warning"
            showIcon
            message="未配置嵌入模型，当前为 BM25 单路检索模式"
            closable
          />
        )}
        <Content style={{ margin: 16 }}>
          <Outlet />
        </Content>
        <Footer style={{ textAlign: 'center', padding: '12px 16px' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            企业RAG管理平台 · Apache-2.0 · @author{' '}
            <Typography.Link href="mailto:owlzhangfq@gmail.com" style={{ fontSize: 12 }}>
              owlzhangfq@gmail.com
            </Typography.Link>
          </Typography.Text>
        </Footer>
      </Layout>
    </Layout>
  );
}

import {
  AppstoreOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  LogoutOutlined,
  MessageOutlined,
  SearchOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Alert, Layout, Menu, Space, Typography } from 'antd';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useModelStatus } from '../context/ModelStatusContext';

const { Header, Sider, Content } = Layout;

const MENU_ITEMS = [
  { key: '/kb', icon: <DatabaseOutlined />, label: '知识库' },
  { key: '/search', icon: <SearchOutlined />, label: '检索调试' },
  { key: '/chat', icon: <MessageOutlined />, label: '问答调试' },
  { key: '/apps', icon: <AppstoreOutlined />, label: '应用中心' },
  { key: '/eval', icon: <ExperimentOutlined />, label: '评测中心' },
  { key: '/settings', icon: <SettingOutlined />, label: '系统设置' },
];

/**
 * Authenticated app shell: left sider navigation + top header + content outlet.
 * Also owns the global "embedding not configured" banner (M1-CONTRACTS.md section 7).
 */
export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { username, logout } = useAuth();
  const { modelStatus } = useModelStatus();

  const selectedKey = MENU_ITEMS.find((item) => location.pathname.startsWith(item.key))?.key ?? '/kb';

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
          items={MENU_ITEMS}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', display: 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>
          <Space>
            <Typography.Text>{username ?? ''}</Typography.Text>
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
      </Layout>
    </Layout>
  );
}

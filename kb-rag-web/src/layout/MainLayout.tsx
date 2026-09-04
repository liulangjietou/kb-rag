// Author: owlzhangfq@gmail.com
import { DownOutlined, LogoutOutlined, MenuOutlined, UserOutlined } from '@ant-design/icons';
import { Alert, Avatar, Breadcrumb, Button, Dropdown, Layout, Menu, Tag } from 'antd';
import type { MenuProps } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { PERMISSIONS } from '../auth/permissions';
import BrandMark from '../components/BrandMark';
import ThemePresetSwitcher from '../components/ThemePresetSwitcher';
import { useModelStatus } from '../context/ModelStatusContext';
import { landingPath, NAV_SECTIONS, visibleNavEntries } from './navigation';

const { Header, Sider, Content } = Layout;
const COMPACT_NAV_QUERY = '(max-width: 991px)';

/**
 * 认证后的全局工作台外壳。菜单只展示账号可访问的页面；路由守卫与服务端仍分别再次鉴权。
 */
export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { displayName, username, canAny, logout } = useAuth();
  const { modelStatus, loading: modelStatusLoading } = useModelStatus();
  const mayReadModelStatus = canAny([PERMISSIONS.SYSTEM_CONFIG, PERMISSIONS.KB_READ]);
  const [compactNavigation, setCompactNavigation] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(COMPACT_NAV_QUERY).matches,
  );
  const [navigationOpen, setNavigationOpen] = useState(false);
  const navigationTriggerRef = useRef<HTMLButtonElement>(null);
  const navigationRegionRef = useRef<HTMLElement>(null);
  const navigationWasOpenRef = useRef(false);

  const navEntries = useMemo(() => visibleNavEntries(canAny), [canAny]);
  const menuItems = useMemo<MenuProps['items']>(
    () =>
      NAV_SECTIONS.map((section) => {
        const children = navEntries
          .filter((entry) => entry.section === section.key)
          .map(({ key, icon, label }) => ({ key, icon, label }));
        return children.length > 0
          ? { key: `section-${section.key}`, type: 'group' as const, label: section.label, children }
          : null;
      }).filter(Boolean) as MenuProps['items'],
    [navEntries],
  );

  const selectedEntry = [...navEntries]
    .filter((entry) => location.pathname === entry.key || location.pathname.startsWith(`${entry.key}/`))
    .sort((a, b) => b.key.length - a.key.length)[0];
  const selectedKey = selectedEntry?.key ?? landingPath(canAny);
  const detailRoute = Boolean(selectedEntry && location.pathname !== selectedEntry.key);

  useEffect(() => {
    const media = window.matchMedia(COMPACT_NAV_QUERY);
    const handleChange = (event: MediaQueryListEvent) => {
      setCompactNavigation(event.matches);
      if (!event.matches) {
        setNavigationOpen(false);
      }
    };
    setCompactNavigation(media.matches);
    media.addEventListener('change', handleChange);
    return () => media.removeEventListener('change', handleChange);
  }, []);

  useEffect(() => {
    setNavigationOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!compactNavigation || !navigationOpen) {
      if (navigationWasOpenRef.current) {
        navigationWasOpenRef.current = false;
        requestAnimationFrame(() => navigationTriggerRef.current?.focus());
      }
      return undefined;
    }

    navigationWasOpenRef.current = true;
    const previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const focusableMenuItems = () =>
      Array.from(
        navigationRegionRef.current?.querySelectorAll<HTMLElement>(
          '[role="menuitem"]:not([aria-disabled="true"])',
        ) ?? [],
      );
    const focusMenuItem = (item: HTMLElement | undefined, items = focusableMenuItems()) => {
      if (!item) {
        return;
      }
      items.forEach((menuItem) => {
        menuItem.tabIndex = menuItem === item ? 0 : -1;
      });
      item.focus();
    };
    const focusFirstMenuItem = () => {
      const items = focusableMenuItems();
      focusMenuItem(items[0], items);
    };
    const focusFrame = requestAnimationFrame(focusFirstMenuItem);
    const focusTimer = window.setTimeout(() => {
      if (!navigationRegionRef.current?.contains(document.activeElement)) {
        focusFirstMenuItem();
      }
    }, 240);

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setNavigationOpen(false);
        return;
      }
      if (event.key === 'Tab') {
        const items = focusableMenuItems();
        if (items.length === 0) {
          return;
        }
        const activeIndex = items.indexOf(document.activeElement as HTMLElement);
        const nextIndex = event.shiftKey
          ? activeIndex <= 0
            ? items.length - 1
            : activeIndex - 1
          : activeIndex < 0 || activeIndex === items.length - 1
            ? 0
            : activeIndex + 1;
        event.preventDefault();
        focusMenuItem(items[nextIndex], items);
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      window.clearTimeout(focusTimer);
      cancelAnimationFrame(focusFrame);
      document.body.style.overflow = previousBodyOverflow;
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [compactNavigation, navigationOpen]);

  const modelHealth = !mayReadModelStatus
    ? { statusClass: 'is-unknown', title: '模型状态按权限隐藏', detail: '当前角色未授权' }
    : modelStatus
    ? modelStatus.embedding_configured
      ? { statusClass: 'is-ready', title: '混合检索已配置', detail: '可用性以请求结果为准' }
      : { statusClass: 'is-degraded', title: 'BM25 检索模式', detail: 'Embedding 尚未配置' }
    : modelStatusLoading
      ? { statusClass: 'is-pending', title: '正在检查模型', detail: '读取服务配置中' }
      : { statusClass: 'is-unknown', title: '模型状态未知', detail: '请检查服务连接' };

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const accountMenu: MenuProps = {
    items: [
      {
        key: 'identity',
        disabled: true,
        label: (
          <div className="account-menu__identity">
            <strong>{displayName ?? '当前账号'}</strong>
            {username && displayName !== username && <small>{username}</small>}
          </div>
        ),
      },
      { type: 'divider' },
      { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true },
    ],
    onClick: ({ key }) => {
      if (key === 'logout') {
        handleLogout();
      }
    },
  };

  return (
    <Layout className="app-shell">
      <Sider
        id="app-primary-navigation"
        className="app-sider"
        width={252}
        collapsedWidth={0}
        trigger={null}
        collapsed={compactNavigation && !navigationOpen}
        role={compactNavigation && navigationOpen ? 'dialog' : undefined}
        aria-modal={compactNavigation && navigationOpen ? true : undefined}
        aria-label="主导航"
      >
        <div className="app-sider__brand">
          <BrandMark inverse />
        </div>
        <div className="workspace-chip" aria-label="当前工作空间：企业知识中台">
          <span>企</span>
          <div>
            <strong>企业知识中台</strong>
            <small>Knowledge workspace</small>
          </div>
        </div>
        <nav ref={navigationRegionRef} className="app-navigation-region" aria-label="功能导航">
          <Menu
            className="app-navigation"
            theme="dark"
            mode="inline"
            selectedKeys={[selectedKey]}
            items={menuItems}
            onClick={({ key }) => navigate(key)}
          />
        </nav>
        <div className="app-sider__health">
          <span className={modelHealth.statusClass} aria-hidden="true" />
          <div>
            <strong>{modelHealth.title}</strong>
            <small>{modelHealth.detail}</small>
          </div>
        </div>
      </Sider>

      {compactNavigation && navigationOpen && (
        <button
          className="navigation-scrim"
          type="button"
          aria-label="关闭主导航"
          onClick={() => setNavigationOpen(false)}
        />
      )}

      <Layout className="app-main">
        <Header className="app-topbar">
          <Button
            ref={navigationTriggerRef}
            className="navigation-trigger"
            type="text"
            icon={<MenuOutlined />}
            aria-label={navigationOpen ? '关闭主导航' : '打开主导航'}
            aria-expanded={navigationOpen}
            aria-controls="app-primary-navigation"
            onClick={() => setNavigationOpen(true)}
          />
          <Breadcrumb
            className="app-breadcrumb"
            items={[
              { title: '控制台' },
              { title: selectedEntry?.label ?? '工作台' },
              ...(detailRoute ? [{ title: '详情' }] : []),
            ]}
          />
          <div className="app-topbar__actions">
            {modelStatus && (
              <Tag className="model-health" color={modelStatus.embedding_configured ? 'success' : 'warning'}>
                {modelStatus.embedding_configured ? 'Embedding 已配置' : 'BM25 模式'}
              </Tag>
            )}
            <ThemePresetSwitcher compact={compactNavigation} />
            <Dropdown menu={accountMenu} trigger={['click']} placement="bottomRight">
              <button className="account-chip" type="button" aria-label="打开账号菜单">
                <Avatar size={30} icon={<UserOutlined />}>
                  {(displayName ?? username ?? '').slice(0, 1).toUpperCase()}
                </Avatar>
                <span>{displayName ?? username ?? '当前账号'}</span>
                <DownOutlined aria-hidden="true" />
              </button>
            </Dropdown>
          </div>
        </Header>

        {modelStatus && !modelStatus.embedding_configured && (
          <Alert
            className="global-model-alert"
            banner
            type="warning"
            showIcon
            message="未配置嵌入模型，当前为 BM25 单路检索模式"
            closable
          />
        )}
        <Content className="app-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

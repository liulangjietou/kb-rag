import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/es/locale/zh_CN';
import { useLayoutEffect } from 'react';
import { BrowserRouter } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import AppRouter from './router/AppRouter';
import { useThemePreset } from './theme/ThemePresetContext';
import { ThemePresetProvider } from './theme/ThemePresetProvider';
import './theme/theme.css';
import './styles/knowledge-workbench.css';
import './styles/catalog-eval.css';
import './styles/management.css';

function ThemedApplication() {
  const { antThemeConfig } = useThemePreset();

  useLayoutEffect(() => {
    ConfigProvider.config({
      holderRender: (children) => (
        <ConfigProvider locale={zhCN} theme={antThemeConfig}>
          {children}
        </ConfigProvider>
      ),
    });
    return () => ConfigProvider.config({ holderRender: undefined });
  }, [antThemeConfig]);

  return (
    <ConfigProvider locale={zhCN} theme={antThemeConfig}>
      <AntApp>
        <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <AuthProvider>
            <AppRouter />
          </AuthProvider>
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  );
}

export default function App() {
  return (
    <ThemePresetProvider>
      <ThemedApplication />
    </ThemePresetProvider>
  );
}

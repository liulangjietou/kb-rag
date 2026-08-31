import type { ReactNode } from 'react';
import BrandMark from './BrandMark';
import RetrievalPipeline from './RetrievalPipeline';
import ThemePresetSwitcher from './ThemePresetSwitcher';

interface AuthShellProps {
  children: ReactNode;
  eyebrow: string;
  headline: string;
  description: string;
  compactCard?: boolean;
}

/** 登录、改密与无权限页共用的公共外壳，业务表单仍由各页面自行管理。 */
export default function AuthShell({ children, eyebrow, headline, description, compactCard = false }: AuthShellProps) {
  return (
    <div className="auth-shell">
      <section className="auth-shell__stage" aria-label="平台能力概览">
        <BrandMark inverse />
        <div className="auth-shell__message">
          <span className="auth-overline">KNOWLEDGE OPERATIONS</span>
          <h1>{headline}</h1>
          <p>{description}</p>
          <RetrievalPipeline />
        </div>
        <footer>
          <span>可追溯检索</span>
          <span>企业级权限</span>
          <span>质量评测闭环</span>
        </footer>
      </section>

      <main className="auth-shell__entry">
        <div className="auth-shell__theme">
          <ThemePresetSwitcher />
        </div>
        <div className="auth-shell__mobile-brand">
          <BrandMark />
        </div>
        <section className={`auth-card${compactCard ? ' auth-card--compact' : ''}`}>
          <span className="auth-overline">{eyebrow}</span>
          {children}
        </section>
        <small className="auth-shell__copyright">Knowledge Atlas · Apache-2.0</small>
      </main>
    </div>
  );
}

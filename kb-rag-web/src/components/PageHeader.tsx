import type { ReactNode } from 'react';

interface PageHeaderProps {
  eyebrow: string;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  before?: ReactNode;
}

/** 全站统一页头：只负责信息层级和响应式布局，不接管任何领域动作。 */
export default function PageHeader({ eyebrow, title, description, actions, before }: PageHeaderProps) {
  return (
    <>
      {before && <div className="page-header__before">{before}</div>}
      <header className="page-header">
        <div className="page-header__copy">
          <span className="page-header__eyebrow" aria-hidden="true">{eyebrow}</span>
          <h1>{title}</h1>
          {description && <p>{description}</p>}
        </div>
        {actions && <div className="page-header__actions">{actions}</div>}
      </header>
    </>
  );
}

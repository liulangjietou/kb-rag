import { NavLink } from 'react-router-dom';

/** 用户列表与注册审核共享上下文，权限不足时不展示审核入口。 */
export default function AccessNavigation({ canReview }: { canReview: boolean }) {
  return (
    <nav className="access-navigation" aria-label="用户与审核">
      <NavLink end to="/users">
        用户列表
      </NavLink>
      {canReview && <NavLink to="/users/registration-reviews">注册审核</NavLink>}
    </nav>
  );
}

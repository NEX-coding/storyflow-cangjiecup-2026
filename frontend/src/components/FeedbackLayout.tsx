import type { ReactNode } from 'react';
import { CommentOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { NavLink, Outlet } from 'react-router-dom';
import { AppLayout } from './AppLayout';
import '../styles/home.css';

const subNavItems = [
  { to: '/feedback', end: true, label: '反馈', icon: <CommentOutlined /> },
  { to: '/feedback/history', end: false, label: '反馈记录', icon: <UnorderedListOutlined /> },
];

export function FeedbackLayout({ children }: { children?: ReactNode }) {
  return (
    <AppLayout>
      <div className="feedback-shell">
        <aside className="feedback-sub-sider">
          <nav className="feedback-sub-nav">
            {subNavItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) =>
                  `feedback-sub-nav-item${isActive ? ' active' : ''}`
                }
              >
                <span className="feedback-sub-nav-icon">{item.icon}</span>
                {item.label}
              </NavLink>
            ))}
          </nav>
        </aside>
        <div className="feedback-main">{children ?? <Outlet />}</div>
      </div>
    </AppLayout>
  );
}

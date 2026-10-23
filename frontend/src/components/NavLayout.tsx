import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  ApiOutlined,
  CommentOutlined,
  FolderOutlined,
  HistoryOutlined,
  HomeOutlined,
  LogoutOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Dropdown, Typography, message } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { getOpenWorkspaceId } from '../lib/openWorkspace';
import { LoginModal } from './LoginModal';

const navItems = [
  { key: '/projects', label: '首页', icon: <HomeOutlined /> },
  { key: '__create__', label: '创作', icon: <FolderOutlined /> },
  { key: '/history', label: '生成历史', icon: <HistoryOutlined /> },
];

interface NavContextValue {
  registerCreateHandler: (handler: (() => void) | null) => void;
  triggerCreate: () => void;
  collapsed: boolean;
  toggleCollapsed: () => void;
}

function isWorkspaceRoute(pathname: string) {
  return /^\/projects\/[^/]+/.test(pathname);
}

const NavContext = createContext<NavContextValue | null>(null);

function useNav() {
  const ctx = useContext(NavContext);
  if (!ctx) {
    throw new Error('useNav must be used within NavProvider');
  }
  return ctx;
}

export function useNavCreate(handler: () => void) {
  const { registerCreateHandler } = useNav();
  const handlerRef = useRef(handler);
  handlerRef.current = handler;
  useEffect(() => {
    registerCreateHandler(() => handlerRef.current());
    return () => registerCreateHandler(null);
  }, [registerCreateHandler]);
}

export function NavProvider({ children }: { children: ReactNode }) {
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(() => isWorkspaceRoute(location.pathname));
  const createHandlerRef = useRef<(() => void) | null>(null);
  const registerCreateHandler = (handler: (() => void) | null) => {
    createHandlerRef.current = handler;
  };
  const triggerCreate = () => createHandlerRef.current?.();

  useEffect(() => {
    setCollapsed(isWorkspaceRoute(location.pathname));
  }, [location.pathname]);

  const toggleCollapsed = () => setCollapsed((v) => !v);

  return (
    <NavContext.Provider value={{ registerCreateHandler, triggerCreate, collapsed, toggleCollapsed }}>
      {children}
    </NavContext.Provider>
  );
}

export function useNavCollapse() {
  const { collapsed, toggleCollapsed } = useNav();
  return { collapsed, toggleCollapsed };
}

export function NavSider() {
  const { triggerCreate, collapsed } = useNav();
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();
  const [loginOpen, setLoginOpen] = useState(false);
  const isGuest = Boolean(user?.guest);

  const selectedKey = location.pathname.startsWith('/ai-config')
    ? '/ai-config'
    : location.pathname.startsWith('/feedback')
      ? '/feedback'
      : location.pathname.startsWith('/history')
        ? '/history'
        : location.pathname.startsWith('/settings')
          ? '/settings'
          : isWorkspaceRoute(location.pathname)
            ? '__create__'
            : '/projects';

  const handleNav = (key: string) => {
    if (key === '__create__') {
      if (isWorkspaceRoute(location.pathname)) {
        return;
      }
      const openId = getOpenWorkspaceId();
      if (openId) {
        navigate(`/projects/${openId}`);
        return;
      }
      if (location.pathname === '/projects') {
        triggerCreate();
      } else {
        navigate('/projects', { state: { openCreate: true } });
      }
      return;
    }
    navigate(key);
  };

  const handleLogout = async () => {
    await logout();
    message.success('已退出登录');
    navigate('/projects');
  };

  return (
    <aside className={`app-sider${collapsed ? ' app-sider--collapsed' : ''}`}>
      <div className="app-sider-inner">
        <div className="app-sider-brand">
          {user ? (
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  {
                    key: 'profile',
                    disabled: true,
                    label: (
                      <div className="app-auth-menu-meta">
                        <Typography.Text strong>{user.nickname}</Typography.Text>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {isGuest ? '免登录访客模式' : user.email}
                        </Typography.Text>
                      </div>
                    ),
                  },
                  { type: 'divider' },
                  ...(isGuest
                    ? [
                        {
                          key: 'login',
                          label: '登录账号',
                          onClick: () => setLoginOpen(true),
                        },
                      ]
                    : [
                        {
                          key: 'logout',
                          icon: <LogoutOutlined />,
                          label: '退出登录',
                          onClick: () => {
                            void handleLogout();
                          },
                        },
                      ]),
                ],
              }}
            >
              <button type="button" className="app-account-entry" title={user.nickname}>
                <span className="app-logo" aria-hidden>
                  <span className="app-logo-clapper" />
                  <span className="app-logo-mark">S</span>
                </span>
                <span className="app-account-label app-account-label--name">{user.nickname}</span>
              </button>
            </Dropdown>
          ) : (
            <button
              type="button"
              className="app-account-entry"
              onClick={() => setLoginOpen(true)}
            >
              <span className="app-logo" aria-hidden>
                <span className="app-logo-clapper" />
                <span className="app-logo-mark">S</span>
              </span>
              <span className="app-account-label">点击登录/账户</span>
            </button>
          )}
        </div>

        <nav className="app-sider-nav">
          {navItems.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`app-nav-item ${selectedKey === item.key ? 'active' : ''}`}
              onClick={() => handleNav(item.key)}
            >
              <span className="app-nav-icon">{item.icon}</span>
              {item.label}
            </button>
          ))}
        </nav>

        <div className="app-sider-footer">
          <button
            type="button"
            className={`app-nav-item subtle ${location.pathname.startsWith('/feedback') ? 'active' : ''}`}
            onClick={() => navigate('/feedback')}
          >
            <span className="app-nav-icon">
              <CommentOutlined />
            </span>
            反馈
          </button>
          <button
            type="button"
            className={`app-nav-item subtle ${location.pathname === '/ai-config' ? 'active' : ''}`}
            onClick={() => navigate('/ai-config')}
          >
            <span className="app-nav-icon">
              <ApiOutlined />
            </span>
            AI 设置
          </button>
          <button
            type="button"
            className={`app-nav-item subtle ${location.pathname === '/settings' ? 'active' : ''}`}
            onClick={() => navigate('/settings')}
          >
            <span className="app-nav-icon">
              <SettingOutlined />
            </span>
            设置
          </button>
        </div>
      </div>

      <LoginModal open={loginOpen} onClose={() => setLoginOpen(false)} />
    </aside>
  );
}
